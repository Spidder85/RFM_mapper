package org.ikozmin.rfm.crypto;

import java.security.Provider;
import java.security.Security;
import org.ikozmin.rfm.exception.RfmCertificateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Регистрирует JCP и JTLS без обращения к JavaCSP. */
public final class CryptoProProviderRegistrar {
    private static final Logger log = LoggerFactory.getLogger(CryptoProProviderRegistrar.class);
    private static final String[] DEFAULT_PROVIDER_CLASSES = {
        "ru.CryptoPro.JCP.JCP",
        "ru.CryptoPro.Crypto.CryptoProvider",
        "ru.CryptoPro.ssl.Provider"
    };

    public void register(String[] configuredProviderClasses) {
        String[] classes = configuredProviderClasses == null || configuredProviderClasses.length == 0
                ? DEFAULT_PROVIDER_CLASSES : configuredProviderClasses;
        for (String name : classes) {
            if (name != null && name.trim().startsWith("ru.CryptoPro.JCSP.")) {
                throw new RfmCertificateException("Remove JCSP from ProviderClasses; use JCP with HDImageStore "
                        + "and migrate the key container first.");
            }
        }
        if (Security.getProvider("JCSP") != null) {
            throw new RfmCertificateException("JCSP is registered globally. Use a Java runtime without "
                    + "the JCSP security provider for JCP mode.");
        }
        for (String name : classes) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                Object instance = Class.forName(name.trim()).getDeclaredConstructor().newInstance();
                if (!(instance instanceof Provider provider)) {
                    throw new IllegalArgumentException("Not a security provider: " + name);
                }
                if (Security.getProvider(provider.getName()) == null) {
                    Security.addProvider(provider);
                }
                Provider actual = Security.getProvider(provider.getName());
                var source = actual.getClass().getProtectionDomain().getCodeSource();
                log.info("CryptoPro provider ready. name={}, version={}, source={}",
                        actual.getName(), actual.getVersionStr(), source == null ? "<runtime>" : source.getLocation());
            } catch (Exception e) {
                throw new RfmCertificateException("Failed to register CryptoPro provider: " + name, e);
            }
        }
        if (Security.getProvider("JCP") == null || Security.getProvider("JTLS") == null) {
            throw new RfmCertificateException("JCP and JTLS providers are required for JCP mode");
        }
    }
}
