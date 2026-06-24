package org.ikozmin.rfm.exception;

public final class RfmCertificateException extends RuntimeException {
    public RfmCertificateException(String message) {
        super(message);
    }

    public RfmCertificateException(String message, Throwable cause) {
        super(message, cause);
    }
}
