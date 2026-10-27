package cn.folotoy.passportnotify;

import org.json.JSONException;
import org.json.JSONObject;

public final class OfficialFirmware {
    public final String slug;
    public final String titleZh;
    public final String titleEn;
    public final String source;
    public final String category;
    public final String keywords;
    public final String shareVersion;
    public final long sourceSize;
    public final String sourceSha256;
    public final String downloadPath;
    public final String format;
    public final String publishedAt;

    OfficialFirmware(String slug, String titleZh, String titleEn, String source,
                     String category, String keywords, String shareVersion,
                     long sourceSize, String sourceSha256, String downloadPath,
                     String format, String publishedAt) {
        this.slug = slug;
        this.titleZh = titleZh;
        this.titleEn = titleEn;
        this.source = source;
        this.category = category;
        this.keywords = keywords;
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

    String sourceLabel() {
        return "official".equals(source) ? "官方" : "社区";
    }

    String categoryLabel() {
        switch (category) {
            case "games": return "游戏";
            case "productivity": return "效率";
            case "information": return "信息";
            case "learning": return "学习";
            case "media": return "媒体";
            case "social": return "社交";
            case "developer": return "开发";
            case "other": return "其他";
            default: return category;
        }
    }

    boolean matches(String query) {
        return CatalogSearch.matches(query, titleZh, titleEn, slug, category, categoryLabel(),
                keywords, sourceLabel());
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("slug", slug);
        json.put("titleZh", titleZh);
        json.put("titleEn", titleEn);
        json.put("source", source);
        json.put("category", category);
        json.put("keywords", keywords);
        json.put("shareVersion", shareVersion);
        json.put("sourceSize", sourceSize);
        json.put("sourceSha256", sourceSha256);
        json.put("downloadPath", downloadPath);
        json.put("format", format);
        json.put("publishedAt", publishedAt);
        return json;
    }

    static OfficialFirmware fromJson(JSONObject json) throws JSONException {
        String downloadPath = json.getString("downloadPath");
        String source = json.optString("source");
        if (source.isEmpty()) {
            source = downloadPath.startsWith("/api/download/community/") ?
                    "community" : "official";
        }
        return new OfficialFirmware(
                json.getString("slug"), json.optString("titleZh"),
                json.optString("titleEn"), source, json.optString("category"),
                json.optString("keywords"), json.optString("shareVersion"),
                json.getLong("sourceSize"), json.getString("sourceSha256"),
                downloadPath, json.getString("format"),
                json.optString("publishedAt"));
    }
}
