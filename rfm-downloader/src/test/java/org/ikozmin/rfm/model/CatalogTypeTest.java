package org.ikozmin.rfm.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogTypeTest {
    @Test
    void shouldParseKnownCatalogs() {
        assertThat(CatalogType.from("te2")).isEqualTo(CatalogType.TE2);
        assertThat(CatalogType.from("te21")).isEqualTo(CatalogType.TE21);
        assertThat(CatalogType.from("mvk")).isEqualTo(CatalogType.MVK);
        assertThat(CatalogType.from("un")).isEqualTo(CatalogType.UN);
        assertThat(CatalogType.from("un-rus")).isEqualTo(CatalogType.UN_RUS);
    }

    @Test
    void shouldRejectUnknownCatalog() {
        assertThatThrownBy(() -> CatalogType.from("wrong"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
