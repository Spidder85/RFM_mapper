package org.ikozmin.rfm.audit;

import java.time.LocalDateTime;

public final class AuditEnvelope {
    private final String createdAt;
    private final String method;
    private final String url;
    private final String requestBody;
    private final Integer responseStatus;
    private final String responseBody;
    private final String note;

    public AuditEnvelope(
            String createdAt,
            String method,
            String url,
            String requestBody,
            Integer responseStatus,
            String responseBody,
            String note
    ) {
        this.createdAt = createdAt;
        this.method = method;
        this.url = url;
        this.requestBody = requestBody;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.note = note;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getMethod() {
        return method;
    }

    public String getUrl() {
        return url;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public String getNote() {
        return note;
    }
}
