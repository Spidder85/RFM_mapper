package org.ikozmin.rfm.client;

import org.ikozmin.rfm.exception.RfmApiException;
import org.ikozmin.rfm.model.CatalogType;

public final class ResponseValidator {
    public void validateFileResponse(CatalogType catalogType, int statusCode, String contentType, long size) {
        if (statusCode < 200 || statusCode >= 300) {
            throw new RfmApiException("File download failed", statusCode, "Binary response body is not logged");
        }

        if (size <= 0) {
            throw new RfmApiException("Downloaded file is empty", statusCode, "Content-Length/body size is zero");
        }

        if (contentType == null || contentType.isBlank()) {
            return;
        }

        String normalized = contentType.toLowerCase();

        if (catalogType == CatalogType.TE2 || catalogType == CatalogType.TE21 || catalogType == CatalogType.MVK) {
            if (!normalized.contains("zip")
                    && !normalized.contains("octet-stream")
                    && !normalized.contains("binary")) {
                throw new RfmApiException(
                        "Unexpected content type for ZIP catalog",
                        statusCode,
                        "Content-Type: " + contentType
                );
            }
            return;
        }

        if (catalogType == CatalogType.UN || catalogType == CatalogType.UN_RUS) {
            if (!normalized.contains("xml")
                    && !normalized.contains("octet-stream")
                    && !normalized.contains("binary")) {
                throw new RfmApiException(
                        "Unexpected content type for XML catalog",
                        statusCode,
                        "Content-Type: " + contentType
                );
            }
        }
    }
}
