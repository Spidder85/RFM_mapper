package org.ikozmin.rfm.exception;

public final class RfmConfigException extends RuntimeException{
    public RfmConfigException(String message) {
        super(message);
    }

    public RfmConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
