package org.ikozmin.rfm.client;

import org.ikozmin.rfm.model.CatalogType;

public final class RfmEndpoints {
    private static final String BASE_URL = "https://portal.fedsfm.ru:8081/Services/fedsfm-service";
    //private static final String BASE_URL = "https://portal.fedsfm.ru/Services/fedsfm-service";

    private final boolean production;

    public RfmEndpoints(boolean production) {
        this.production = production;
    }

    public String authenticateUrl() {
        return BASE_URL + (production ? "/authenticate" : "/test-contur/authenticate");
    }

    public String catalogUrl(CatalogType catalogType) {
        if (!production) {
            return switch (catalogType) {
                case TE2, TE21 -> BASE_URL + "/test-contur/suspect-catalogs/current-te2-catalog";
                case MVK -> BASE_URL + "/test-contur/suspect-catalogs/current-mvk-catalog";
                case UN, UN_RUS -> throw new IllegalArgumentException("UN catalogs are not described for test contour");
                default -> throw new IllegalArgumentException("Unsupported catalog: " + catalogType);
            };
        }

        return switch (catalogType) {
            case TE2 -> BASE_URL + "/suspect-catalogs/current-te2-catalog";
            case TE21 -> BASE_URL + "/suspect-catalogs/current-te21-catalog";
            case MVK -> BASE_URL + "/suspect-catalogs/current-mvk-catalog";
            case UN -> BASE_URL + "/suspect-catalogs/current-un-catalog";
            case UN_RUS -> BASE_URL + "/suspect-catalogs/current-un-catalog-rus";
            default -> throw new IllegalArgumentException("Unsupported catalog: " + catalogType);
        };
    }

    public String fileUrl(CatalogType catalogType) {
        if (!production) {
            return switch (catalogType) {
                case TE2, TE21 -> BASE_URL + "/test-contur/suspect-catalogs/current-te2-file";
                case MVK -> BASE_URL + "/test-contur/suspect-catalogs/current-mvk-file-zip";
                case UN, UN_RUS -> throw new IllegalArgumentException("UN files are not described for test contour");
                default -> throw new IllegalArgumentException("Unsupported catalog: " + catalogType);
            };
        }

        return switch (catalogType) {
            case TE2 -> BASE_URL + "/suspect-catalogs/current-te2-file";
            case TE21 -> BASE_URL + "/suspect-catalogs/current-te21-file";
            case MVK -> BASE_URL + "/suspect-catalogs/current-mvk-file-zip";
            case UN, UN_RUS -> BASE_URL + "/suspect-catalogs/current-un-file";
            default -> throw new IllegalArgumentException("Unsupported catalog: " + catalogType);
        };
    }
}
