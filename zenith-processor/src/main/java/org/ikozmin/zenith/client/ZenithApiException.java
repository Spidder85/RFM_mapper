package org.ikozmin.zenith.client;

public final class ZenithApiException extends RuntimeException {
    private final String operation;
    private final int status;
    private final String body;

    public ZenithApiException(String operation, int status, String body) {
        super("Zenith API error. operation=" + operation + ", status=" + status + ", body=" + body);
        this.operation = operation;
        this.status = status;
        this.body = body;
    }

    public String operation() {
        return operation;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }

    public boolean isObsoletePersonListImport() {
        if (!"import person list".equals(operation)) {
            return false;
        }

        if (status != 400) {
            return false;
        }

        if (body == null) {
            return false;
        }

        String normalized = body.toLowerCase();

        return normalized.contains("уже загружен список")
                && normalized.contains("неактуален");
    }
}
