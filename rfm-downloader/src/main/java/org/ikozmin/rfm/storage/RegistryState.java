package org.ikozmin.rfm.storage;

public final class RegistryState {
    private final String idXml;
    private final String date;
    private final String file;
    private final String downloadedAt;
    private final String sha256;

    public RegistryState(String idXml, String date, String file, String downloadedAt, String sha256) {
        this.idXml = idXml;
        this.date = date;
        this.file = file;
        this.downloadedAt = downloadedAt;
        this.sha256 = sha256;
    }

    public String getIdXml() {
        return idXml;
    }

    public String getDate() {
        return date;
    }

    public String getFile() {
        return file;
    }

    public String getDownloadedAt() {
        return downloadedAt;
    }

    public String getSha256() {
        return sha256;
    }
}
