package org.ikozmin.rfm.model;

import java.util.Locale;

/** Доступные контуры API: тестовый и продуктивный. */
public enum Contour {
    PROD,
    TEST;

    public boolean isProduction() {
        return this == PROD;
    }

    public static Contour from(boolean useTestContour) {
        return useTestContour ? TEST : PROD;
    }

    public static Contour fromCliValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Contour value is empty");
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);

        return switch (normalized) {
            case "prod", "production" -> PROD;
            case "test", "test-contur", "test-contour" -> TEST;
            default -> throw new IllegalArgumentException("Unsupported contour: " + value);
        };
    }
}
