package org.ikozmin.rfm.cert;

import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.Locale;

import org.ikozmin.rfm.exception.RfmCertificateException;
import org.ikozmin.rfm.logging.Masking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class CertificateLoader {
    private static final Logger log = LoggerFactory.getLogger(CertificateLoader.class);

    public ClientCertificate loadFromWindowsMy(String serialNumber) {
        try {
            log.info("Loading client certificate from Windows-MY by serial {}", Masking.serial(serialNumber));

            KeyStore keyStore = KeyStore.getInstance("Windows-MY-CURRENTUSER");
            keyStore.load(null, null);

            // Подсчёт всех сертификатов в хранилище
            Enumeration<String> allAliases = keyStore.aliases();
            int totalCount = 0;
            while (allAliases.hasMoreElements()) {
                allAliases.nextElement();
                totalCount++;
            }
            log.info("Total certificates in Windows-MY-CURRENTUSER store: {}", totalCount);
            // ========================================
            String alias = findAliasBySerial(keyStore, serialNumber);

            log.info("Client certificate selected. alias={}", alias);
            return new ClientCertificate(keyStore, alias);
        } catch (Exception e) {
            throw new RfmCertificateException("Failed to load client certificate from Windows CurrentUser/My", e);
        }
    }

    private String findAliasBySerial(KeyStore keyStore, String serialNumber) throws Exception {
        String expected = normalizeSerial(serialNumber);

        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            Certificate certificate = keyStore.getCertificate(alias);

            if (!(certificate instanceof X509Certificate)) {
                continue;
            }

            X509Certificate x509 = (X509Certificate) certificate;
            String actual = normalizeSerial(x509.getSerialNumber().toString(16));

            if (actual.equals(expected)) {
                if (!keyStore.isKeyEntry(alias)) {
                    throw new RfmCertificateException("Certificate found, but private key is unavailable. Alias: " + alias);
                }

                return alias;
            }
        }

        throw new RfmCertificateException("Certificate not found in Windows CurrentUser/My. Serial: " + Masking.serial(serialNumber));
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
}
