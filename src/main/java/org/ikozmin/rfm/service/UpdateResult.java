package org.ikozmin.rfm.service;

import java.nio.file.Path;

public final class UpdateResult {
    private final boolean downloaded;
    private final String idXml;
    private final Path file;

    public UpdateResult(boolean downloaded, String idXml, Path file) {
        this.downloaded = downloaded;
        this.idXml = idXml;
        this.file = file;
    }

    public boolean isDownloaded() {
        return downloaded;
    }

    public String getIdXml() {
        return idXml;
    }

    public Path getFile() {
        return file;
    }
}
