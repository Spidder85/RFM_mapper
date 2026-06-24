package org.ikozmin.rfm.service;

import org.ikozmin.rfm.model.CatalogInfo;
import org.ikozmin.rfm.model.CatalogType;

public final class DownloadRequestIdResolver {
    public String resolve(CatalogType catalogType, CatalogInfo catalogInfo) {
        return switch (catalogType) {
            case TE2 -> firstNotBlank(
                    catalogInfo.getIdXml(),
                    catalogInfo.getIdDbf(),
                    catalogInfo.getIdDoc()
            );
            case TE21, MVK, UN, UN_RUS -> catalogInfo.requireIdXml();
            default -> throw new IllegalArgumentException("Unsupported catalog type: " + catalogType);
        };
    }

    private String firstNotBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }

        throw new IllegalStateException("Catalog response does not contain downloadable file id");
    }
}
