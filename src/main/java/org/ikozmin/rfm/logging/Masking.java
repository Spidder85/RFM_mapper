package org.ikozmin.rfm.logging;

public final class Masking {
    private Masking() {}

    public static String secret(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }

        return "***";
    }

    public static String token(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }

        if (value.length() <= 12) {
            return "***";
        }

        return value.substring(0, 6) + "***" + value.substring(value.length() - 6);
    }

    public static String serial(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }

        String normalized = value.replace(" ", "").replace("-", "");

        if (normalized.length() <= 8) {
            return "***";
        }

        return normalized.substring(0, 4) + "***" + normalized.substring(normalized.length() - 4);
    }
}
