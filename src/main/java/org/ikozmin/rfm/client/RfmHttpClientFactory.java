package org.ikozmin.rfm.client;

import java.net.http.HttpClient;
import java.security.KeyStore;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.time.Duration;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;

import org.ikozmin.rfm.cert.CertificateKeyManager;
import org.ikozmin.rfm.cert.ClientCertificate;
import org.ikozmin.rfm.config.AppConfig;
import org.ikozmin.rfm.exception.RfmCertificateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.TrustManager;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;

public final class RfmHttpClientFactory {
    private static final Logger log = LoggerFactory.getLogger(RfmHttpClientFactory.class);

    public HttpClient create(ClientCertificate certificate, AppConfig.Certificate certificateConfig) {
        if (certificateConfig.isUseCryptoPro()) {
            return createCryptoProClient(certificate, certificateConfig.getCryptoPro());
        }
        return createDefaultClient(certificate);
    }

    private HttpClient createDefaultClient(ClientCertificate certificate) {
        try {
            log.info("Creating default Java TLS HTTP client");

            SSLContext sslContext = createSslContext(
                certificate,
                "TLS",
                null,
                null

            );

            return buildHttpClient(sslContext);
        } catch (Exception e) {
            throw new RfmCertificateException("Failed to create default TLS HTTP client", e);
        }
    }

    private HttpClient createCryptoProClient(ClientCertificate certificate, AppConfig.CryptoPro cryptoPro) {
        try {
            String sslProtocol = valueOrDefault(
                    cryptoPro == null ? null : cryptoPro.getSslProtocol(),
                    "GostTLS"
            );

            String sslProvider = trimToNull(
                    cryptoPro == null ? null : cryptoPro.getSslProvider()
            );

            log.info("Creating CryptoPro TLS HTTP client. protocol={}, provider={}",
                sslProtocol,
                sslProvider == null ? "<default>" : sslProvider);

            SSLContext sslContext = createSslContext(
                certificate,
                sslProtocol,
                sslProvider,
                cryptoPro
            );

            return buildHttpClient(sslContext);
        } catch (Exception e) {
            throw new RfmCertificateException("Failed to create CryptoPro/JTLS HTTP client", e);
        }
    }

    private SSLContext createSslContext(
            ClientCertificate certificate,
            String sslProtocol,
            String sslProvider,
            AppConfig.CryptoPro cryptoPro
    ) throws Exception {
        KeyManagerFactory keyManagerFactory = createKeyManagerFactory(cryptoPro);

        keyManagerFactory.init(certificate.getKeyStore(), new char[0]);

        X509ExtendedKeyManager originalKeyManager = extractX509KeyManager(keyManagerFactory);
        CertificateKeyManager fixedAliasKeyManager = new CertificateKeyManager(
                originalKeyManager,
                certificate.getAlias()
        );

        TrustManagerFactory trustManagerFactory = createTrustManagerFactory(cryptoPro);
        trustManagerFactory.init(createTrustStore(cryptoPro));

        SSLContext sslContext = sslProvider == null
                ? SSLContext.getInstance(sslProtocol)
                : SSLContext.getInstance(sslProtocol, sslProvider);

        sslContext.init(
                new KeyManager[]{fixedAliasKeyManager},
                trustManagerFactory.getTrustManagers(),
                SecureRandom.getInstanceStrong()
        );

        logInstalledSecurityProviders();

        return sslContext;
    }

    private HttpClient buildHttpClient(SSLContext sslContext) {
        return HttpClient.newBuilder()
            .sslContext(sslContext)
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    private X509ExtendedKeyManager extractX509KeyManager(KeyManagerFactory keyManagerFactory) {
        for (KeyManager keyManager : keyManagerFactory.getKeyManagers()) {
            if (keyManager instanceof X509ExtendedKeyManager) {
                return (X509ExtendedKeyManager) keyManager;
            }
        }

        throw new RfmCertificateException("X509ExtendedKeyManager not found");
    }

    private void logInstalledSecurityProviders() {
        StringBuilder builder = new StringBuilder();

        for (Provider provider : Security.getProviders()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }

            builder.append(provider.getName());
        }

        log.info("Installed security providers: {}", builder);
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private KeyManagerFactory createKeyManagerFactory(AppConfig.CryptoPro cryptoPro) throws Exception {
        String algorithm = valueOrDefault(
                cryptoPro == null ? null : cryptoPro.getKeyManagerAlgorithm(),
                KeyManagerFactory.getDefaultAlgorithm()
        );

        String provider = trimToNull(
                cryptoPro == null ? null : cryptoPro.getKeyManagerProvider()
        );

        return provider == null
                ? KeyManagerFactory.getInstance(algorithm)
                : KeyManagerFactory.getInstance(algorithm, provider);
    }

    private TrustManagerFactory createTrustManagerFactory(AppConfig.CryptoPro cryptoPro) throws Exception {
        String algorithm = valueOrDefault(
                cryptoPro == null ? null : cryptoPro.getTrustManagerAlgorithm(),
                TrustManagerFactory.getDefaultAlgorithm()
        );

        String provider = trimToNull(
                cryptoPro == null ? null : cryptoPro.getTrustManagerProvider()
        );

        return provider == null
                ? TrustManagerFactory.getInstance(algorithm)
                : TrustManagerFactory.getInstance(algorithm, provider);
    }

    private KeyStore createTrustStore(AppConfig.CryptoPro cryptoPro) throws Exception {
        String trustStoreType = trimToNull(cryptoPro == null ? null : cryptoPro.getTrustStoreType());
        if (trustStoreType == null) {
            return null;
        }

        String trustStoreProvider = trimToNull(cryptoPro.getTrustStoreProvider());

        KeyStore trustStore = trustStoreProvider == null
                ? KeyStore.getInstance(trustStoreType)
                : KeyStore.getInstance(trustStoreType, trustStoreProvider);

        trustStore.load(null, null);
        return trustStore;
    }
}
