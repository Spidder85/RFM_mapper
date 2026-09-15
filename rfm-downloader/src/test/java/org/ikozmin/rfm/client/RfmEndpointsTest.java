package org.ikozmin.rfm.client;

import org.ikozmin.rfm.model.CatalogType;
import org.ikozmin.rfm.model.Contour;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RfmEndpointsTest {
    @Test
    void shouldBuildProdTe21Urls() {
        RfmEndpoints endpoints = new RfmEndpoints(Contour.PROD);

        assertThat(endpoints.authenticateUrl())
                .isEqualTo("https://portal.fedsfm.ru:8081/Services/fedsfm-service/authenticate");

        assertThat(endpoints.catalogUrl(CatalogType.TE21))
                .endsWith("/suspect-catalogs/current-te21-catalog");

        assertThat(endpoints.fileUrl(CatalogType.TE21))
                .endsWith("/suspect-catalogs/current-te21-file");
    }

    @Test
    void shouldBuildTestTe2UrlsForTe21Alias() {
        RfmEndpoints endpoints = new RfmEndpoints(Contour.TEST);

        assertThat(endpoints.catalogUrl(CatalogType.TE21))
                .endsWith("/test-contur/suspect-catalogs/current-te2-catalog");

        assertThat(endpoints.fileUrl(CatalogType.TE21))
                .endsWith("/test-contur/suspect-catalogs/current-te2-file");
    }
}
