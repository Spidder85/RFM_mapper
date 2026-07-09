package org.ikozmin.rfm.cert;

import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.Locale;

import org.ikozmin.rfm.config.AppConfig;
import org.ikozmin.rfm.crypto.CryptoProProviderRegistrar;
import org.ikozmin.rfm.exception.RfmCertificateException;
import org.ikozmin.common.logging.Masking;
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
                "REGISTRY"
            );

            String keyStoreProvider = trimToNull(
                valueOrDefault(
                    cryptoPro == null ? null : cryptoPro.getKeyStoreProvider(),
                    "JCSP"
                )
            );

            log.info("Loading CryptoPro certificate. keyStoreType={}, keyStoreProvider={}, serial={}",
                keyStoreType,
                keyStoreProvider == null ? "<default>" : keyStoreProvider,
                Masking.serial(serialNumber));
            
            KeyStore keyStore = keyStoreProvider == null
                ? KeyStore.getInstance(keyStoreType)
                : KeyStore.getInstance(keyStoreType, keyStoreProvider);

            keyStore.load(null, null);

            log.info("CryptoPro key store opened. type={}, provider={}",
                    keyStoreType,
                    keyStoreProvider == null ? "<default>" : keyStoreProvider);

            String alias = findAliasBySerial(keyStore, serialNumber);

            log.info("CryptoPro client certificate selected. alias={}", alias);
            return new ClientCertificate(keyStore, alias);
        } catch (Exception e) {
            throw new RfmCertificateException("Failed to load CryptoPro client certificate", e);
        }
    }

    private String findAliasBySerial(KeyStore keyStore, String serialNumber) throws Exception {
        // Ожидаемый серийный номер (убираем пробелы)
        String expected = serialNumber.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        log.debug("Looking for serial: {}", Masking.serial(expected));
        
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            Certificate certificate = keyStore.getCertificate(alias);
            
            if (!(certificate instanceof X509Certificate x509)) {
                continue;
            }
            
            // Получаем серийный номер в разных форматах
            String serialHex = x509.getSerialNumber().toString(16).toLowerCase(Locale.ROOT);
            
            byte[] bytes = x509.getSerialNumber().toByteArray();
            StringBuilder hexBytes = new StringBuilder();
            for (byte b : bytes) {
                hexBytes.append(String.format("%02x", b & 0xFF));
            }
            String serialBytes = hexBytes.toString();
            
            log.debug("Comparing - expected: {}, hex: {}, bytes: {}",
                    Masking.serial(expected),
                    Masking.serial(serialHex),
                    Masking.serial(serialBytes)
            );
            
            // Сравниваем в разных форматах
            if (serialHex.equals(expected) || serialBytes.equals(expected)) {
                if (!keyStore.isKeyEntry(alias)) {
                    throw new RfmCertificateException("Certificate found, but private key is unavailable. Alias: " + alias);
                }
                return alias;
            }
        }
        
        throw new RfmCertificateException("Certificate not found in CryptoPro key store. Serial: " + Masking.serial(serialNumber));
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
