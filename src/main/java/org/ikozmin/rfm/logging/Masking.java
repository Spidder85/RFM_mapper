package org.ikozmin.rfm.logging;

public final class Masking {
    private Masking() {}

    public static String secret(String value) {
        if (isBlank(value)) {
            return "<empty>";
        }

        return "***";
    }

    public static String token(String value) {
        if (isBlank(value)) {
            return "<empty>";
        }

        if (value.length() <= 12) {
            return "***";
        }

        return value.substring(0, 6) + "***" + value.substring(value.length() - 6);
    }

    public static String serial(String value) {
        if (isBlank(value)) {
            return "<empty>";
        }

        String normalized = value
                .replace(" ", "")
                .replace(":", "")
                .replace("-", "");

        return middleMask(normalized, 4, 4);
    }

    public static String userName(String value) {
        if (isBlank(value)) {
            return "<empty>";
        }

        return middleMask(value, 4, 3);
    }

    public static String id(String value) {
        if (isBlank(value)) {
            return "<empty>";
        }

        return middleMask(value, 8, 4);
    }

    public static String subject(String value) {
        if (isBlank(value)) {
            return "<empty>";
        }

        return "<hidden>";
    }

    private static String middleMask(String value, int prefix, int suffix) {
        if (value.length() <= prefix + suffix) {
            return "***";
        }

        return value.substring(0, prefix) + "***" + value.substring(value.length() - suffix);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
