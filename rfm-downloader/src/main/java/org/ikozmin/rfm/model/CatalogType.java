package org.ikozmin.rfm.model;

import java.util.Locale;

public enum CatalogType {
    TE2("te2", "suspect", "zip"),
    TE21("te21", "suspect", "zip"),
    MVK("mvk", "freeze", "zip"),
    UN("un", "un", "xml"),
    UN_RUS("un-rus", "un-rus", "xml");

    private final String code;
    private final String filePrefix;
    private final String extension;

    CatalogType(String code, String filePrefix, String extension) {
        this.code = code;
        this.filePrefix = filePrefix;
        this.extension = extension;
    }

    public String getCode() {
        return code;
    }

    public String getFilePrefix() {
        return filePrefix;
    }

    public String getExtension() {
        return extension;
    }

    public static CatalogType from(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);

        return switch (normalized) {
            case "te2" -> TE2;
            case "te21", "terrorist", "terrorists", "suspect" -> TE21;
            case "mvk", "freeze" -> MVK;
            case "un" -> UN;
            case "un-rus", "un_rus", "unrus" -> UN_RUS;
            default -> throw new IllegalArgumentException("Unsupported catalog type: " + value);
        };
    }
}
