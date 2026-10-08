package cn.folotoy.passportnotify;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class FirmwareRepository {
    public static final long DEVICE_SLOT_SIZE = 0x1d0000L;
    private static final String PREFS = "firmware_library";
    private static final String KEY_ITEMS = "items";
    private static final int APP_DESCRIPTOR_OFFSET = 32;
    private static final int IMAGE_CHIP_ID_OFFSET = 12;
    private static final int ESP32_C3_CHIP_ID = 0x0005;
    private final Context context;
    private final File directory;

    public FirmwareRepository(Context context) {
        this.context = context.getApplicationContext();
        this.directory = new File(this.context.getFilesDir(), "firmwares");
        if (!directory.exists()) directory.mkdirs();
    }

    public synchronized List<FirmwareImage> list() {
        List<FirmwareImage> items = new ArrayList<>();
        String raw = preferences().getString(KEY_ITEMS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject json = array.getJSONObject(index);
                File file = new File(directory, json.getString("file"));
                if (!file.isFile()) continue;
                items.add(new FirmwareImage(
                        json.getString("id"), json.optString("name"),
                        json.optString("original"), json.optString("project"),
                        json.optString("version"), json.optLong("size"),
                        json.optString("sha256"), json.optLong("importedAt"), file));
            }
        } catch (JSONException ignored) {
            items.clear();
        }
        items.sort(Comparator.comparingLong((FirmwareImage item) -> item.importedAt).reversed());
        return items;
    }

    public synchronized FirmwareImage importFrom(Uri uri) throws IOException {
        String originalName = queryName(uri);
        if (!originalName.toLowerCase(Locale.ROOT).endsWith(".bin")) {
            throw new IOException("请选择 ESP32-C3 应用固件 .bin 文件");
        }

        File temporary = File.createTempFile("import-", ".bin", directory);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("系统不支持 SHA-256", error);
        }

        long size = 0;
        try (InputStream source = new BufferedInputStream(
                     context.getContentResolver().openInputStream(uri));
             BufferedOutputStream target = new BufferedOutputStream(new FileOutputStream(temporary))) {
            if (source == null) throw new IOException("无法读取所选文件");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = source.read(buffer)) != -1) {
                size += count;
                if (size > DEVICE_SLOT_SIZE) {
                    throw new IOException("固件超过设备玩法槽上限 1.81 MiB");
                }
                digest.update(buffer, 0, count);
                target.write(buffer, 0, count);
            }
        } catch (IOException error) {
            temporary.delete();
            throw error;
        }

        if (size < 176) {
            temporary.delete();
            throw new IOException("文件过小，不是有效的 ESP32-C3 应用固件");
        }
        byte[] header = new byte[176];
        try (FileInputStream input = new FileInputStream(temporary)) {
            int position = 0;
            while (position < header.length) {
                int count = input.read(header, position, header.length - position);
                if (count < 0) break;
                position += count;
            }
        }
        int chipId = (header[IMAGE_CHIP_ID_OFFSET] & 0xff) |
                ((header[IMAGE_CHIP_ID_OFFSET + 1] & 0xff) << 8);
        if ((header[0] & 0xff) != 0xe9 ||
                (header[APP_DESCRIPTOR_OFFSET] & 0xff) != 0x32 ||
                (header[APP_DESCRIPTOR_OFFSET + 1] & 0xff) != 0x54 ||
                (header[APP_DESCRIPTOR_OFFSET + 2] & 0xff) != 0xcd ||
                (header[APP_DESCRIPTOR_OFFSET + 3] & 0xff) != 0xab) {
            temporary.delete();
            throw new IOException("不是可加载的应用固件；请勿选择从 0x0 烧录的 full.bin");
        }
        if (chipId != ESP32_C3_CHIP_ID) {
            temporary.delete();
            throw new IOException("固件目标芯片不是 ESP32-C3，不能安装到 AI Passport");
        }

        String sha256 = hex(digest.digest());
        String id = sha256.substring(0, 16);
        String project = cString(header, 80, 32);
        String version = cString(header, 48, 32);
        String baseName = originalName.substring(0, originalName.length() - 4);
        String name = project.isBlank() ? baseName : project;
        File destination = new File(directory, id + ".bin");
        if (destination.exists()) destination.delete();
        if (!temporary.renameTo(destination)) {
            copy(temporary, destination);
            temporary.delete();
        }

        List<FirmwareImage> items = list();
        for (FirmwareImage old : new ArrayList<>(items)) {
            if (old.id.equals(id)) items.remove(old);
        }
        FirmwareImage image = new FirmwareImage(id, name, originalName, project, version,
                size, sha256, System.currentTimeMillis(), destination);
        items.add(0, image);
        save(items);
        return image;
    }

    public synchronized void delete(FirmwareImage image) {
        List<FirmwareImage> items = list();
        items.removeIf(item -> item.id.equals(image.id));
        image.file.delete();
        save(items);
    }

    private void save(List<FirmwareImage> items) {
        JSONArray array = new JSONArray();
        for (FirmwareImage item : items) {
            try {
                JSONObject json = new JSONObject();
                json.put("id", item.id);
                json.put("name", item.name);
                json.put("original", item.originalName);
                json.put("project", item.project);
                json.put("version", item.version);
                json.put("size", item.size);
                json.put("sha256", item.sha256);
                json.put("importedAt", item.importedAt);
                json.put("file", item.file.getName());
                array.put(json);
            } catch (JSONException ignored) { }
        }
        preferences().edit().putString(KEY_ITEMS, array.toString()).apply();
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String queryName(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(0);
                if (value != null && !value.isBlank()) return value;
            }
        } catch (RuntimeException ignored) { }
        String last = uri.getLastPathSegment();
        return last == null || last.isBlank() ? "firmware.bin" : last;
    }

    private static String cString(byte[] data, int offset, int length) {
        int end = offset;
        while (end < offset + length && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static String hex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte value : data) builder.append(String.format("%02x", value & 0xff));
        return builder.toString();
    }

    private static void copy(File source, File destination) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }
}
