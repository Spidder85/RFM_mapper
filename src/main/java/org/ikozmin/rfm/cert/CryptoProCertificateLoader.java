package org.ikozmin.rfm.cert;

import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.Locale;

import org.ikozmin.rfm.config.AppConfig;
import org.ikozmin.rfm.crypto.CryptoProProviderRegistrar;
import org.ikozmin.rfm.exception.RfmCertificateException;
import org.ikozmin.rfm.logging.Masking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CryptoProCertificateLoader {
    private static final Logger log = LoggerFactory.getLogger(CryptoProCertificateLoader.class);

    public ClientCertificate load(AppConfig.Certificate certificateConfig, String serialNumber) {
        try {
            AppConfig.CryptoPro cryptoPro = certificateConfig.getCryptoPro();

            new CryptoProProviderRegistrar().register(
                cryptoPro == null ? null : cryptoPro.getProviderClasses()
            );

            String keyStoreType = valueOrDefault(
                cryptoPro == null ? null : cryptoPro.getKeyStoreType(),
                "HDImageStore"  // JCP 2.0 использует HDImageStore
            );

            String keyStoreProvider = trimToNull(
                cryptoPro == null ? null : cryptoPro.getKeyStoreProvider()
            );

            log.info("Loading CryptoPro certificate. keyStoreType={}, keyStoreProvider={}, serial={}",
                keyStoreType,
                keyStoreProvider == null ? "<default>" : keyStoreProvider,
                Masking.serial(serialNumber));
            
            KeyStore keyStore = keyStoreProvider == null
                ? KeyStore.getInstance(keyStoreType)
                : KeyStore.getInstance(keyStoreType, keyStoreProvider);

            keyStore.load(null, null);

            String alias = findAliasBySerial(keyStore, serialNumber);

            log.info("CryptoPro client certificate selected. alias={}", alias);
            return new ClientCertificate(keyStore, alias);
        } catch (Exception e) {
            throw new RfmCertificateException("Failed to load CryptoPro client certificate", e);
        }
    }

    private String findAliasBySerial(KeyStore keyStore, String serialNumber) throws Exception {
        String expected = normalizeSerial(serialNumber);

        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            Certificate certificate = keyStore.getCertificate(alias);

            if (!(certificate instanceof X509Certificate x509)) {
                continue;
            }

            // Правильное получение серийного номера через байты
            byte[] bytes = x509.getSerialNumber().toByteArray();
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b & 0xFF));
            }

            String actual = normalizeSerial(x509.getSerialNumber().toString(16));

            if (actual.equals(expected)) {
                if (!keyStore.isKeyEntry(alias)) {
                    throw new RfmCertificateException("Certificate found, but private key is unavailable. Alias: " + alias);
                }

                return alias;
            }
        }

        throw new RfmCertificateException(
                "Certificate not found in CryptoPro key store. Serial: " + Masking.serial(serialNumber)
        );
    }

    private String normalizeSerial(String value) {
        if (value == null) {
            return "";
        }

        return value
            .replace(" ", "")
            .replace(":", "")
            .replace("-", "")
            .toLowerCase(Locale.ROOT);
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
