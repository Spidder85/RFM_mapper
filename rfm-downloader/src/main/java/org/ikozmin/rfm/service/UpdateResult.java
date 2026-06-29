package org.ikozmin.rfm.service;

import org.ikozmin.rfm.model.CatalogType;

import java.nio.file.Path;

public record UpdateResult(
        boolean downloaded,
        CatalogType catalogType,
        String oldIdXml,
        String idXml,
        Path registryFile,
        Path archiveFile,
        String sha256,
        long fileSize,
        String downloadedAt
) {
    public boolean isDownloaded() {
        return downloaded;
    }

    public String getIdXml() {
        return idXml;
    }

    public Path getFile() {
        return registryFile;
    }

    public Path file() {
        return registryFile;
    }
}