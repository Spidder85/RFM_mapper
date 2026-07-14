package org.ikozmin.rfm.crypto;

import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;

import org.ikozmin.rfm.exception.RfmCertificateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Регистрирует JCP, JCSP и JTLS провайдеры CryptoPro в JVM. */
public final class CryptoProProviderRegistrar {
    private static final Logger log = LoggerFactory.getLogger(CryptoProProviderRegistrar.class);

    private static final String[] DEFAULT_PROVIDER_CLASSES = {
        "ru.CryptoPro.JCSP.JCSP",
        "ru.CryptoPro.JCP.JCP",
        "ru.CryptoPro.Crypto.CryptoProvider",
        "ru.CryptoPro.ssl.Provider"
    };

    public void register(String[] configuredProviderClasses) {
        String[] providerClasses = configuredProviderClasses == null || configuredProviderClasses.length == 0
            ? DEFAULT_PROVIDER_CLASSES
            : configuredProviderClasses;

        List<String> loaded = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (String providerClass : providerClasses) {
            if (providerClass == null || providerClass.trim().isEmpty()) {
                continue;
            }

            try {
                Provider provider = instantiateProvider(providerClass.trim());

                if (Security.getProvider(provider.getName()) == null) {
                    Security.addProvider(provider);
                    log.info("CryptoPro provider registered. name={}, class={}", provider.getName(), providerClass);
                } else {
                    log.info("CryptoPro provider already registered. name={}, class={}", provider.getName(), providerClass);
                }

                loaded.add(provider.getName());
            } catch (Exception e) {
                log.warn("CryptoPro provider was not registered. class={}, error={}", providerClass, e.getMessage());
                failed.add(providerClass);
            }
        }

        // ========== ПРИНУДИТЕЛЬНАЯ УСТАНОВКА ПОРЯДКА ПРОВАЙДЕРОВ ==========
        try {
            Provider jcsp = Security.getProvider("JCSP");
            Provider jcp = Security.getProvider("JCP");

            if (jcsp != null && jcp != null) {
                Security.removeProvider("JCSP");
                Security.removeProvider("JCP");
                Security.insertProviderAt(jcsp, 1);
                Security.insertProviderAt(jcp, 2);
                log.info("Providers reordered: JCSP at position 1, JCP at position 2");
            }
        } catch (Exception e) {
            log.warn("Failed to reorder providers: {}", e.getMessage());
        }

        if (loaded.isEmpty()) {
            throw new RfmCertificateException("No CryptoPro providers were registered. Failed classes: " + failed);
        }

        // регистрация алгоритмов подписи
        try {
            Provider jcspProvider = Security.getProvider("JCSP");
            if (jcspProvider != null) {
                String[] algorithms = {
                    "GOST3411-2012withGOST3410-2012-256",
                    "GOST3411withGOST3410EL",
                    "GOST3411withGOST3410",
                    "GOST3411-2012withGOST3410-2012"
                };

                for (String alg : algorithms) {
                    try {
                        jcspProvider.put("Signature." + alg, "ru.CryptoPro.JCP.Sign.cl_0");
                        log.info("Added signature algorithm to JCSP: {}", alg);
                    } catch (Exception e) {
                        log.debug("Could not add algorithm {}: {}", alg, e.getMessage());
                    }
                }
                log.info("JCSP signature algorithms configured");
            }
        } catch (Exception e) {
            log.warn("Failed to configure JCSP signature algorithms: {}", e.getMessage());
        }

        log.info("CryptoPro providers ready: {}", loaded);
    }

    private Provider instantiateProvider(String className) throws Exception {
        Class<?> clazz = Class.forName(className);
        Object instance = clazz.getDeclaredConstructor().newInstance();

        if (!(instance instanceof Provider)) {
            throw new IllegalStateException("Class is not java.security.Provider: " + className);
        }

        return (Provider) instance;
    }
}
