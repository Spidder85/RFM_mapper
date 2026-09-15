package org.ikozmin.rfm.exception;

/** Ошибка поиска или использования клиентского сертификата. */
public final class RfmCertificateException extends RuntimeException {
    public RfmCertificateException(String message) {
        super(message);
    }

    public RfmCertificateException(String message, Throwable cause) {
        super(message, cause);
    }
}
