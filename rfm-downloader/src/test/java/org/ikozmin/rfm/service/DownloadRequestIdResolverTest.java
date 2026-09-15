package org.ikozmin.rfm.service;

import org.ikozmin.rfm.model.CatalogInfo;
import org.ikozmin.rfm.model.CatalogType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadRequestIdResolverTest {
    private final DownloadRequestIdResolver resolver = new DownloadRequestIdResolver();

    @Test
    void shouldUseIdXmlForTe21() {
        CatalogInfo info = new CatalogInfo();
        info.setIdXml("xml-id");

        assertThat(resolver.resolve(CatalogType.TE21, info)).isEqualTo("xml-id");
    }

    @Test
    void shouldUseIdXmlForTe2ByDefault() {
        CatalogInfo info = new CatalogInfo();
        info.setIdXml("xml-id");
        info.setIdDbf("dbf-id");

        assertThat(resolver.resolve(CatalogType.TE2, info)).isEqualTo("xml-id");
    }
}