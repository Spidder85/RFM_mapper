package org.ikozmin.rfm.crypto;

import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;

import org.ikozmin.rfm.exception.RfmCertificateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CryptoProProviderRegistrar {
    private static final Logger log = LoggerFactory.getLogger(CryptoProProviderRegistrar.class);

    private static final String[] DEFAULT_PROVIDER_CLASSES = {
        "ru.CryptoPro.JCP.JCP",
        "ru.CryptoPro.JCSP.JCSP",
        "ru.CryptoPro.RevCheck.RevCheck",
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

        if (loaded.isEmpty()) {
            throw new RfmCertificateException("No CryptoPro providers were registered. Failed classes: " + failed);
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
