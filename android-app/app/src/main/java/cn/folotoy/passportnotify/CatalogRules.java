package cn.folotoy.passportnotify;

final class CatalogRules {
    private static final String DOWNLOAD_ROOT = "/api/download/";

    private CatalogRules() { }

    static boolean isPublishedSource(String source, String status) {
        return "published".equals(status) &&
                ("official".equals(source) || "community".equals(source));
    }

    static String expectedDownloadPath(String source, String slug) {
        return DOWNLOAD_ROOT + source + "/" + slug;
    }

    static boolean hasExpectedDownloadPath(String source, String slug, String path) {
        return ("official".equals(source) || "community".equals(source)) &&
                expectedDownloadPath(source, slug).equals(path);
    }
}
