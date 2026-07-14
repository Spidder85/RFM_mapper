package org.ikozmin.zenith.service;

import org.ikozmin.zenith.config.ZenithConfig;

import java.util.Locale;

/** Выбирает формат импорта Zenith по коду реестра, а не по пользовательской настройке. */
public final class ZenithImportFormatResolver {
    public ImportFormat resolve(String catalog, ZenithConfig.Import importConfig) {
        String normalize = catalog == null
                ? ""
                : catalog.trim().toLowerCase(Locale.ROOT);

        return switch (normalize) {
            case "te2", "te21" -> new ImportFormat("TerroristsXml", null);
            case "un", "un-rus", "un_rus", "unrus" -> new ImportFormat(
                    "UnXml",
                    requireListCategory("un", importConfig)
            );
            case "mvk" -> new ImportFormat(
                    "CftXml",
                    requireListCategory("mvk", importConfig)
            );
            default -> throw new IllegalArgumentException("Unsupported Zenith import catalog: " + catalog);
        };
    }

    private String requireListCategory(String catalog, ZenithConfig.Import importConfig) {
        String value = importConfig == null ? null : importConfig.getListCategory(catalog);

        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Zenith list_category is required for catalog "
                            + catalog
                            + ". Configure Zenith.Import.ListCategories."
            );
        }

        return value.trim();
    }

    public record ImportFormat(
            String fileFormat,
            String listCategory
    ) {
    }
}
