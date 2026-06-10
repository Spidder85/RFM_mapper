package org.ikozmin.rfm.client;

import org.ikozmin.rfm.cert.CertificateKeyManager;
import org.ikozmin.rfm.cert.ClientCertificate;
import org.ikozmin.rfm.exception.RfmCertificateException;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;

public final class RfmHttpClientFactory {
    public HttpClient create(ClientCertificate certificate) {
        try {
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm()
            );

            keyManagerFactory.init(certificate.getKeyStore(), new char[0]);

            X509ExtendedKeyManager originalKeyManager = extractX509KeyManager(keyManagerFactory);
            CertificateKeyManager fixedAliasKeyManager = new CertificateKeyManager(
                    originalKeyManager,
                    certificate.getAlias()
            );

            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm()
            );

            trustManagerFactory.init((KeyStore) null);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(
                    new KeyManager[]{fixedAliasKeyManager},
                    trustManagerFactory.getTrustManagers(),
                    SecureRandom.getInstanceStrong()
            );

            return HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        } catch (Exception e) {
            throw new RfmCertificateException("Failed to create mTLS HTTP client", e);
        }
    }

    private X509ExtendedKeyManager extractX509KeyManager(KeyManagerFactory keyManagerFactory) {
        for (KeyManager keyManager : keyManagerFactory.getKeyManagers()) {
            if (keyManager instanceof X509ExtendedKeyManager) {
                return (X509ExtendedKeyManager) keyManager;
            }
        }

        throw new RfmCertificateException("X509ExtendedKeyManager not found");
    }
}
