package org.ikozmin.zenith.config;

import java.util.Locale;

public enum ZenithWorkflowMode {
    IMPORT_ONLY,
    CHECK_ONLY,
    FULL;

    public static ZenithWorkflowMode from(String value) {
        if (value == null || value.isBlank()) {
            return FULL;
        }

        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "IMPORT_ONLY", "IMPORT" -> IMPORT_ONLY;
            case "CHECK_ONLY", "CHECK" -> CHECK_ONLY;
            case "FULL", "ALL" -> FULL;
            default -> throw new IllegalArgumentException("Unsupported Zenith workflow mode: " + value);
        };
    }
}
