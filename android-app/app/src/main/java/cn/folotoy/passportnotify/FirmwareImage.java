package cn.folotoy.passportnotify;

import java.io.File;

public final class FirmwareImage {
    public final String id;
    public final String name;
    public final String originalName;
    public final String project;
    public final String version;
    public final long size;
    public final String sha256;
    public final long importedAt;
    public final File file;
    public final String officialSlug;
    public final String sourceSha256;

    FirmwareImage(String id, String name, String originalName, String project,
                  String version, long size, String sha256, long importedAt, File file,
                  String officialSlug, String sourceSha256) {
        this.id = id;
        this.name = name;
        this.originalName = originalName;
        this.project = project;
        this.version = version;
        this.size = size;
        this.sha256 = sha256;
        this.importedAt = importedAt;
        this.file = file;
        this.officialSlug = officialSlug;
        this.sourceSha256 = sourceSha256;
    }

    public String displayName() {
        return name == null || name.trim().isEmpty() ? originalName : name;
    }
}
