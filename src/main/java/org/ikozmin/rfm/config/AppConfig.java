package org.ikozmin.rfm.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class AppConfig {
    @JsonProperty("Credentials")
    private Credentials credentials;

    @JsonProperty("Certificate")
    private Certificate certificate;

    @JsonProperty("Logging")
    private Logging logging;

    @JsonProperty("DefaultCatalog")
    private String defaultCatalog;

    @JsonProperty("UseTestContour")
    private boolean useTestContour;

    public Credentials getCredentials() {
        return credentials;
    }

    public Certificate getCertificate() {
        return certificate;
    }

    public Logging getLogging() {
        return logging;
    }

    public String getDefaultCatalog() {
        return defaultCatalog;
    }

    public boolean isUseTestContour() {
        return useTestContour;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Credentials {
        @JsonProperty("UserName")
        private String userName;

        @JsonProperty("Password")
        private String password;

        public String getUserName() {
            return userName;
        }

        public String getPassword() {
            return password;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Certificate {
        @JsonProperty("SerialNumber")
        private String serialNumber;

        @JsonProperty("StoreLocation")
        private String storeLocation;

        @JsonProperty("StoreName")
        private String storeName;

        @JsonProperty("CertPfxPath")
        private String certPfxPath;

        @JsonProperty("CertPfxPassword")
        private String certPfxPassword;

        @JsonProperty("UseCryptoPro")
        private boolean useCryptoPro;

        @JsonProperty("CryptoPro")
        private CryptoPro cryptoPro;

        public String getSerialNumber() {
            return serialNumber;
        }

        public String getStoreLocation() {
            return storeLocation;
        }

        public String getStoreName() {
            return storeName;
        }

        public String getCertPfxPath() {
            return certPfxPath;
        }

        public String getCertPfxPassword() {
            return certPfxPassword;
        }

        public boolean isUseCryptoPro() {
            return useCryptoPro;
        }

        public CryptoPro getCryptoPro() {
            return cryptoPro;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class CryptoPro {
        @JsonProperty("ProviderClasses")
        private String[] providerClasses;

        @JsonProperty("KeyStoreType")
        private String keyStoreType;

        @JsonProperty("KeyStoreProvider")
        private String keyStoreProvider;

        @JsonProperty("SslProtocol")
        private String sslProtocol;

        @JsonProperty("SslProvider")
        private String sslProvider;

        public String[] getProviderClasses() {
            return providerClasses;
        }

        public String getKeyStoreType() {
            return keyStoreType;
        }

        public String getKeyStoreProvider() {
            return keyStoreProvider;
        }

        public String getSslProtocol() {
            return sslProtocol;
        }

        public String getSslProvider() {
            return sslProvider;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Logging {
        @JsonProperty("LogFileName")
        private String logFileName;

        @JsonProperty("LogLevel")
        private String logLevel;

        public String getLogFileName() {
            return logFileName;
        }

        public String getLogLevel() {
            return logLevel;
        }
    }
}
