package org.ikozmin.rfm.exception;

/** Ошибка аутентификации пользователя в API Росфинмониторинга. */
public final class RfmAuthException extends RuntimeException {
    public RfmAuthException(String message) {
        super(message);
    }

    public RfmAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
