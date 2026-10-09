package cn.folotoy.passportnotify;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
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
    public static final long DEVICE_SLOT_SIZE = 0x400000L;
    private static final long MERGED_APP_OFFSET = 0x10000L;
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
                String original = json.optString("original");
                String project = json.optString("project");
                String name = displayName(json.optString("name"), original, project);
                items.add(new FirmwareImage(
                        json.getString("id"), name, original, project,
                        json.optString("version"), json.optLong("size"),
                        json.optString("sha256"), json.optLong("importedAt"), file,
                        json.optString("officialSlug"), json.optString("sourceSha256")));
            }
        } catch (JSONException ignored) {
            items.clear();
        }
        items.sort(Comparator.comparingLong((FirmwareImage item) -> item.importedAt).reversed());
        return items;
    }

    public synchronized FirmwareImage findOfficial(OfficialFirmware official) {
        for (FirmwareImage image : list()) {
            if (official.slug.equals(image.officialSlug) &&
                    official.sourceSha256.equalsIgnoreCase(image.sourceSha256)) return image;
        }
        return null;
    }

    public synchronized FirmwareImage importOfficial(InputStream input,
                                                     OfficialFirmware official,
                                                     ProgressListener listener)
            throws IOException {
        if (official.sourceSize <= 0 || official.sourceSize > DEVICE_SLOT_SIZE) {
            throw new IOException("官网固件超过设备玩法槽上限 4.00 MiB");
        }
        File sourceFile = File.createTempFile("official-source-", ".bin", directory);
        MessageDigest sourceDigest = sha256Digest();
        long sourceSize = 0;
        int lastPercent = -1;
        try (InputStream source = new BufferedInputStream(input);
             BufferedOutputStream target = new BufferedOutputStream(
                     new FileOutputStream(sourceFile))) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = source.read(buffer)) != -1) {
                sourceSize += count;
                if (sourceSize > DEVICE_SLOT_SIZE || sourceSize > official.sourceSize) {
                    throw new IOException("官网下载内容超过目录记录或 4.00 MiB 上限");
                }
                sourceDigest.update(buffer, 0, count);
                target.write(buffer, 0, count);
                int percent = (int)((sourceSize * 100) / official.sourceSize);
                if (listener != null && percent != lastPercent) {
                    lastPercent = percent;
                    listener.onProgress(Math.min(percent, 100));
                }
            }
        } catch (IOException error) {
            sourceFile.delete();
            throw error;
        }

        if (sourceSize != official.sourceSize) {
            sourceFile.delete();
            throw new IOException("官网下载不完整，实际大小与官网记录不一致");
        }
        String sourceSha256 = hex(sourceDigest.digest());
        if (!sourceSha256.equalsIgnoreCase(official.sourceSha256)) {
            sourceFile.delete();
            throw new IOException("官网固件 SHA-256 校验失败，已拒绝保存");
        }
        if (!"esp-merged-0x0".equals(official.format)) {
            sourceFile.delete();
            throw new IOException("暂不支持官网返回的固件格式：" + official.format);
        }

        long appSize = sourceSize - MERGED_APP_OFFSET;
        if (appSize < 176 || appSize > DEVICE_SLOT_SIZE) {
            sourceFile.delete();
            throw new IOException("官网固件中的应用镜像大小不适合设备玩法槽");
        }
        ImageMetadata metadata;
        File appFile = null;
        try {
            metadata = validateImage(sourceFile, MERGED_APP_OFFSET);
            appFile = File.createTempFile("official-app-", ".bin", directory);
            extract(sourceFile, MERGED_APP_OFFSET, appFile);
        } catch (IOException error) {
            if (appFile != null) appFile.delete();
            throw error;
        } finally {
            sourceFile.delete();
        }

        String appSha256 = digestFile(appFile);
        String id = appSha256.substring(0, 16);
        File destination = new File(directory, id + ".bin");
        if (destination.exists() && !destination.delete()) {
            appFile.delete();
            throw new IOException("无法替换手机中的旧固件文件");
        }
        if (!appFile.renameTo(destination)) {
            try {
                copy(appFile, destination);
            } catch (IOException error) {
                destination.delete();
                appFile.delete();
                throw error;
            }
            appFile.delete();
        }

        List<FirmwareImage> items = list();
        for (FirmwareImage old : new ArrayList<>(items)) {
            if (old.id.equals(id) || official.slug.equals(old.officialSlug)) {
                items.remove(old);
                if (!old.file.equals(destination)) old.file.delete();
            }
        }
        String originalName = official.slug + "-user-slot.bin";
        FirmwareImage image = new FirmwareImage(id, official.displayName(), originalName,
                metadata.project, metadata.version, appSize, appSha256,
                System.currentTimeMillis(), destination, official.slug, sourceSha256);
        items.add(0, image);
        save(items);
        if (listener != null) listener.onProgress(100);
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
                json.put("officialSlug", item.officialSlug);
                json.put("sourceSha256", item.sourceSha256);
                array.put(json);
            } catch (JSONException ignored) { }
        }
        preferences().edit().putString(KEY_ITEMS, array.toString()).apply();
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static ImageMetadata validateImage(File file, long offset) throws IOException {
        if (offset < 0 || file.length() - offset < 176) {
            throw new IOException("文件过小，不是有效的 ESP32-C3 应用固件");
        }
        byte[] header = new byte[176];
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(offset);
            input.readFully(header);
        }
        int chipId = (header[IMAGE_CHIP_ID_OFFSET] & 0xff) |
                ((header[IMAGE_CHIP_ID_OFFSET + 1] & 0xff) << 8);
        if ((header[0] & 0xff) != 0xe9 ||
                (header[APP_DESCRIPTOR_OFFSET] & 0xff) != 0x32 ||
                (header[APP_DESCRIPTOR_OFFSET + 1] & 0xff) != 0x54 ||
                (header[APP_DESCRIPTOR_OFFSET + 2] & 0xff) != 0xcd ||
                (header[APP_DESCRIPTOR_OFFSET + 3] & 0xff) != 0xab) {
            throw new IOException("官网文件未包含可加载的 ESP32-C3 应用镜像");
        }
        if (chipId != ESP32_C3_CHIP_ID) {
            throw new IOException("官网固件目标芯片不是 ESP32-C3，不能安装到 AI Passport");
        }
        return new ImageMetadata(cString(header, 80, 32), cString(header, 48, 32));
    }

    private static void extract(File source, long offset, File destination) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(source, "r");
             BufferedOutputStream output = new BufferedOutputStream(
                     new FileOutputStream(destination))) {
            input.seek(offset);
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    private static MessageDigest sha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("系统不支持 SHA-256", error);
        }
    }

    private static String digestFile(File file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String displayName(String storedName, String originalName, String project) {
        String name = storedName == null ? "" : storedName.trim();
        boolean genericProject = "FoloToy-AI-Passport".equalsIgnoreCase(project) ||
                "FoloToy-AI-Passport".equalsIgnoreCase(name);
        if (!name.isEmpty() && !genericProject) return name;

        String fileName = originalName == null ? "" : originalName.trim();
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".bin")) {
            fileName = fileName.substring(0, fileName.length() - 4);
        }
        if (fileName.endsWith("-user-slot")) {
            fileName = fileName.substring(0, fileName.length() - "-user-slot".length());
        }
        if (!fileName.trim().isEmpty()) return fileName;
        return project == null || project.trim().isEmpty() ? "官方玩法" : project;
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
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    public interface ProgressListener {
        void onProgress(int percent);
    }

    private static final class ImageMetadata {
        final String project;
        final String version;

        ImageMetadata(String project, String version) {
            this.project = project;
            this.version = version;
        }
    }
}
