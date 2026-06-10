package org.ikozmin.rfm.cert;

import java.security.KeyStore;

public final class ClientCertificate {
    private final KeyStore keyStore;
    private final String alias;

    public ClientCertificate(KeyStore keyStore, String alias) {
        this.keyStore = keyStore;
        this.alias = alias;
    }

    public KeyStore getKeyStore() {
        return keyStore;
    }

    public String getAlias() {
        return alias;
    }
}
