package cn.folotoy.passportnotify;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class OfficialFirmwareService {
    private static final String OFFICIAL_ORIGIN = "https://ai-passport.folotoy.cn";
    private static final String CATALOG_PATH = "/api/plays";
    private static final String DOWNLOAD_PREFIX = "/api/download/official/";
    private static final String PREFS = "official_firmware_catalog";
    private static final String KEY_CACHE = "cache";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 60000;
    private static final int MAX_CATALOG_BYTES = 8 * 1024 * 1024;
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,80}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final Context context;

    public OfficialFirmwareService(Context context) {
        this.context = context.getApplicationContext();
    }

    public CatalogResult loadCached() {
        String raw = preferences().getString(KEY_CACHE, "");
        if (raw.isEmpty()) return CatalogResult.empty();
        try {
            return CatalogResult.fromJson(new JSONObject(raw));
        } catch (JSONException ignored) {
            return CatalogResult.empty();
        }
    }

    public CatalogResult refreshCatalog() throws IOException {
        HttpURLConnection connection = open(new URL(OFFICIAL_ORIGIN + CATALOG_PATH));
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("官网固件列表请求失败：HTTP " + status);
            }
            byte[] payload;
            try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                payload = readLimited(input, MAX_CATALOG_BYTES);
            }
            CatalogResult result = parseCatalog(new JSONObject(
                    new String(payload, StandardCharsets.UTF_8)));
            preferences().edit().putString(KEY_CACHE, result.toJson().toString()).apply();
            return result;
        } catch (JSONException error) {
            throw new IOException("官网固件列表格式异常", error);
        } finally {
            connection.disconnect();
        }
    }

    public DownloadResult download(OfficialFirmware firmware, FirmwareRepository repository,
                                   FirmwareRepository.ProgressListener listener) throws IOException {
        validateOfficialPath(firmware);
        URL url = new URL(OFFICIAL_ORIGIN + firmware.downloadPath);
        HttpURLConnection connection = open(url);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("官网下载失败：HTTP " + status);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > 0 && contentLength != firmware.sourceSize) {
                throw new IOException("官网下载大小与目录记录不一致");
            }
            FirmwareImage image;
            try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                image = repository.importOfficial(input, firmware, listener);
            }
            try {
                String location = exportToDownloads(image, firmware);
                return new DownloadResult(image, location);
            } catch (IOException error) {
                repository.delete(image);
                throw error;
            } catch (RuntimeException error) {
                repository.delete(image);
                throw new IOException("无法保存到系统下载目录", error);
            }
        } finally {
            connection.disconnect();
        }
    }

    private CatalogResult parseCatalog(JSONObject root) throws JSONException, IOException {
        JSONArray plays = root.optJSONArray("plays");
        if (plays == null) throw new IOException("官网未返回玩法列表");
        List<OfficialFirmware> items = new ArrayList<>();
        int officialCount = 0;
        int tooLargeCount = 0;
        int unsupportedCount = 0;
        for (int index = 0; index < plays.length(); index++) {
            JSONObject play = plays.optJSONObject(index);
            if (play == null || !"official".equals(play.optString("source")) ||
                    !play.optBoolean("isOfficial", false) ||
                    !"published".equals(play.optString("status"))) continue;
            officialCount++;
            JSONObject firmware = play.optJSONObject("firmware");
            long size = firmware == null ? play.optLong("firmwareSize") :
                    firmware.optLong("size", play.optLong("firmwareSize"));
            if (size > FirmwareRepository.DEVICE_SLOT_SIZE) {
                tooLargeCount++;
                continue;
            }
            String slug = play.optString("slug");
            String sha = firmware == null ? play.optString("firmwareSha256") :
                    firmware.optString("sha256", play.optString("firmwareSha256"));
            String path = firmware == null ? play.optString("downloadUrl") :
                    firmware.optString("url", play.optString("downloadUrl"));
            String format = firmware == null ? "" : firmware.optString("format");
            JSONObject title = play.optJSONObject("title");
            if (size <= 0 || !SLUG.matcher(slug).matches() || !SHA256.matcher(sha).matches() ||
                    !path.equals(DOWNLOAD_PREFIX + slug) || !"esp-merged-0x0".equals(format) ||
                    title == null) {
                unsupportedCount++;
                continue;
            }
            items.add(new OfficialFirmware(slug, title.optString("zh"), title.optString("en"),
                    play.optString("shareVersion"), size, sha.toLowerCase(Locale.ROOT), path,
                    format, play.optString("publishedAt")));
        }
        items.sort(Comparator.comparing((OfficialFirmware item) -> item.publishedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return new CatalogResult(items, officialCount, tooLargeCount, unsupportedCount,
                System.currentTimeMillis());
    }

    private void validateOfficialPath(OfficialFirmware firmware) throws IOException {
        if (!SLUG.matcher(firmware.slug).matches() ||
                !firmware.downloadPath.equals(DOWNLOAD_PREFIX + firmware.slug) ||
                !SHA256.matcher(firmware.sourceSha256).matches() ||
                firmware.sourceSize <= 0 || firmware.sourceSize > FirmwareRepository.DEVICE_SLOT_SIZE ||
                !"esp-merged-0x0".equals(firmware.format)) {
            throw new IOException("固件目录记录未通过安全校验");
        }
    }

    private HttpURLConnection open(URL url) throws IOException {
        if (!"https".equals(url.getProtocol()) || !"ai-passport.folotoy.cn".equals(url.getHost())) {
            throw new IOException("拒绝访问非 FoloToy 官方地址");
        }
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json, application/octet-stream");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "AI-Passport-Portal-Android/1.5");
        return connection;
    }

    private String exportToDownloads(FirmwareImage image, OfficialFirmware firmware)
            throws IOException {
        String fileName = firmware.slug + (firmware.shareVersion.isEmpty() ? "" :
                "-" + safePart(firmware.shareVersion)) + "-user-slot.bin";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/AI-Passport");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("无法在系统下载目录创建文件");
            try (FileInputStream input = new FileInputStream(image.file);
                 OutputStream output = context.getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new IOException("无法写入系统下载目录");
                copy(input, output);
            } catch (IOException error) {
                context.getContentResolver().delete(uri, null, null);
                throw error;
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Downloads.IS_PENDING, 0);
            if (context.getContentResolver().update(uri, ready, null, null) != 1) {
                context.getContentResolver().delete(uri, null, null);
                throw new IOException("系统下载目录未确认保存结果");
            }
        } else {
            File directory = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "AI-Passport");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("无法创建 Download/AI-Passport 目录");
            }
            File destination = new File(directory, fileName);
            try (FileInputStream input = new FileInputStream(image.file);
                 FileOutputStream output = new FileOutputStream(destination, false)) {
                copy(input, output);
            }
        }
        return "Download/AI-Passport/" + fileName;
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static byte[] readLimited(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maximum) throw new IOException("官网固件列表超过安全大小限制");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16384];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    private static String safePart(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "-");
        return safe.isEmpty() ? "official" : safe;
    }

    public static final class CatalogResult {
        public final List<OfficialFirmware> items;
        public final int officialCount;
        public final int tooLargeCount;
        public final int unsupportedCount;
        public final long fetchedAt;

        CatalogResult(List<OfficialFirmware> items, int officialCount, int tooLargeCount,
                      int unsupportedCount, long fetchedAt) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.officialCount = officialCount;
            this.tooLargeCount = tooLargeCount;
            this.unsupportedCount = unsupportedCount;
            this.fetchedAt = fetchedAt;
        }

        static CatalogResult empty() {
            return new CatalogResult(Collections.emptyList(), 0, 0, 0, 0);
        }

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            JSONArray array = new JSONArray();
            for (OfficialFirmware item : items) array.put(item.toJson());
            json.put("items", array);
            json.put("officialCount", officialCount);
            json.put("tooLargeCount", tooLargeCount);
            json.put("unsupportedCount", unsupportedCount);
            json.put("fetchedAt", fetchedAt);
            return json;
        }

        static CatalogResult fromJson(JSONObject json) throws JSONException {
            JSONArray array = json.getJSONArray("items");
            List<OfficialFirmware> items = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                items.add(OfficialFirmware.fromJson(array.getJSONObject(index)));
            }
            return new CatalogResult(items, json.optInt("officialCount", items.size()),
                    json.optInt("tooLargeCount"), json.optInt("unsupportedCount"),
                    json.optLong("fetchedAt"));
        }
    }

    public static final class DownloadResult {
        public final FirmwareImage image;
        public final String downloadLocation;

        DownloadResult(FirmwareImage image, String downloadLocation) {
            this.image = image;
            this.downloadLocation = downloadLocation;
        }
    }
}
