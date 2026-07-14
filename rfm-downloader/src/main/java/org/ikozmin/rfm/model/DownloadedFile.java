package org.ikozmin.rfm.model;

import java.nio.file.Path;

/** Результат скачивания файла реестра во временный путь. */
public final class DownloadedFile {
    private final Path path;
    private final String contentType;
    private final long size;

    public DownloadedFile(Path path, String contentType, long size) {
        this.path = path;
        this.contentType = contentType;
        this.size = size;
    }

    public Path getPath() {
        return path;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSize() {
        return size;
    }
}
