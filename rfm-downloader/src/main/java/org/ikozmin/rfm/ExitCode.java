package org.ikozmin.rfm;

import org.ikozmin.rfm.exception.RfmApiException;
import org.ikozmin.rfm.exception.RfmAuthException;
import org.ikozmin.rfm.exception.RfmCertificateException;
import org.ikozmin.rfm.exception.RfmConfigException;

public enum ExitCode {
    OK(0),
    GENERAL_ERROR(1),
    CONFIG_ERROR(2),
    CERTIFICATE_ERROR(3),
    AUTH_ERROR(4),
    API_ERROR(5);

    private final int code;

    ExitCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ExitCode from(Throwable throwable) {
        if (throwable instanceof RfmConfigException) {
            return CONFIG_ERROR;
        }

        if (throwable instanceof RfmCertificateException) {
            return CERTIFICATE_ERROR;
        }

        if (throwable instanceof RfmAuthException) {
            return AUTH_ERROR;
        }

        if (throwable instanceof RfmApiException) {
            return API_ERROR;
        }

        return GENERAL_ERROR;
    }
}
