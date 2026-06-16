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
                "Windows-MY"
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

            // ========== ОТЛАДКА: вывод всех сертификатов ==========
            log.info("=== All certificates in JCP HDImageStore ===");
            Enumeration<String> allAliases = keyStore.aliases();
            int count = 0;
            while (allAliases.hasMoreElements()) {
                count++;
                String alias = allAliases.nextElement();
                Certificate cert = keyStore.getCertificate(alias);
                if (cert instanceof X509Certificate) {
                    X509Certificate x509 = (X509Certificate) cert;
                    // Серийный номер в разных форматах
                    String serialHex = x509.getSerialNumber().toString(16);
                    byte[] bytes = x509.getSerialNumber().toByteArray();
                    StringBuilder hexBytes = new StringBuilder();
                    for (byte b : bytes) {
                        hexBytes.append(String.format("%02x", b & 0xFF));
                    }
                    log.info("  [{}] Alias: {}", count, alias);
                    log.info("      Serial (toString16): {}", serialHex);
                    log.info("      Serial (bytes): {}", hexBytes.toString());
                    log.info("      Subject: {}", x509.getSubjectDN());
                    log.info("      Has private key: {}", keyStore.isKeyEntry(alias));
                }
            }
            log.info("Total certificates in JCP store: {}", count);
            // =====================================================


            String alias = findAliasBySerial(keyStore, serialNumber);

            log.info("CryptoPro client certificate selected. alias={}", alias);
            return new ClientCertificate(keyStore, alias);
        } catch (Exception e) {
            throw new RfmCertificateException("Failed to load CryptoPro client certificate", e);
        }
    }

    private String findAliasBySerial(KeyStore keyStore, String serialNumber) throws Exception {
        // String expected = normalizeSerial(serialNumber);

        // Enumeration<String> aliases = keyStore.aliases();
        // while (aliases.hasMoreElements()) {
        //     String alias = aliases.nextElement();
        //     Certificate certificate = keyStore.getCertificate(alias);

        //     if (!(certificate instanceof X509Certificate x509)) {
        //         continue;
        //     }

        //     // Правильное получение серийного номера через байты
        //     byte[] bytes = x509.getSerialNumber().toByteArray();
        //     StringBuilder hex = new StringBuilder();
        //     for (byte b : bytes) {
        //         hex.append(String.format("%02x", b & 0xFF));
        //     }

        //     String actual = normalizeSerial(x509.getSerialNumber().toString(16));

        //     if (actual.equals(expected)) {
        //         if (!keyStore.isKeyEntry(alias)) {
        //             throw new RfmCertificateException("Certificate found, but private key is unavailable. Alias: " + alias);
        //         }

        //         return alias;
        //     }
        // }

        // throw new RfmCertificateException(
        //         "Certificate not found in CryptoPro key store. Serial: " + Masking.serial(serialNumber)
        // );
        // Ожидаемый серийный номер (убираем пробелы)
        String expected = serialNumber.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        log.debug("Looking for serial: {}", expected);
        
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
            
            log.debug("Comparing - expected: {}, hex: {}, bytes: {}", expected, serialHex, serialBytes);
            
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
