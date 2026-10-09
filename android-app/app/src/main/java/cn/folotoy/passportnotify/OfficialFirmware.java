package cn.folotoy.passportnotify;

import org.json.JSONException;
import org.json.JSONObject;

public final class OfficialFirmware {
    public final String slug;
    public final String titleZh;
    public final String titleEn;
    public final String shareVersion;
    public final long sourceSize;
    public final String sourceSha256;
    public final String downloadPath;
    public final String format;
    public final String publishedAt;

    OfficialFirmware(String slug, String titleZh, String titleEn, String shareVersion,
                     long sourceSize, String sourceSha256, String downloadPath,
                     String format, String publishedAt) {
        this.slug = slug;
        this.titleZh = titleZh;
        this.titleEn = titleEn;
        this.shareVersion = shareVersion;
        this.sourceSize = sourceSize;
        this.sourceSha256 = sourceSha256;
        this.downloadPath = downloadPath;
        this.format = format;
        this.publishedAt = publishedAt;
    }

    String displayName() {
        if (titleZh != null && !titleZh.trim().isEmpty()) return titleZh.trim();
        if (titleEn != null && !titleEn.trim().isEmpty()) return titleEn.trim();
        return slug;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("slug", slug);
        json.put("titleZh", titleZh);
        json.put("titleEn", titleEn);
        json.put("shareVersion", shareVersion);
        json.put("sourceSize", sourceSize);
        json.put("sourceSha256", sourceSha256);
        json.put("downloadPath", downloadPath);
        json.put("format", format);
        json.put("publishedAt", publishedAt);
        return json;
    }

    static OfficialFirmware fromJson(JSONObject json) throws JSONException {
        return new OfficialFirmware(
                json.getString("slug"), json.optString("titleZh"),
                json.optString("titleEn"), json.optString("shareVersion"),
                json.getLong("sourceSize"), json.getString("sourceSha256"),
                json.getString("downloadPath"), json.getString("format"),
                json.optString("publishedAt"));
    }
}
