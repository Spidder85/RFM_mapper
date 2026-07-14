package org.ikozmin.rfm.exception;

/** Ошибка HTTP-взаимодействия с API Росфинмониторинга. */
public final class RfmApiException extends RuntimeException {
    private final int statusCode;
    private final String responseBody;

    public RfmApiException(String message, int statusCode, String responseBody) {
        super(message + ". HTTP " + statusCode + ". Body: " + responseBody);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
