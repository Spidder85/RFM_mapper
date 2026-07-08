package org.ikozmin.rfm.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

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

    @JsonProperty("Catalogs")
    private List<String> catalogs;

    @JsonProperty("UseTestContour")
    private boolean useTestContour;

    @JsonProperty("Notifications")
    private NotificationsConfig notifications;

    @JsonProperty("OutputDirectory")
    private OutputConfig output;

    @JsonProperty("Retention")
    private RetentionConfig retention;

    @JsonProperty("Events")
    private EventsConfig events;

    @JsonProperty("ZenithTrigger")
    private ZenithTriggerConfig zenithTrigger;

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

    public List<String> getCatalogs() {
        return catalogs == null ? List.of() : catalogs;
    }

    public boolean isUseTestContour() {
        return useTestContour;
    }

    public NotificationsConfig getNotifications() {
        return notifications;
    }

    public OutputConfig getOutputDirectory() {
        return output;
    }

    public RetentionConfig getRetention() {
        return retention;
    }

    public EventsConfig getEvents() {
        return events;
    }

    public ZenithTriggerConfig getZenithTrigger() {
        return zenithTrigger;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class OutputConfig {
        @JsonProperty("Path")
        private String path;

        @JsonProperty("Catalogs")
        private Map<String, String> catalogs;

        public String getPath() {
            return path;
        }

        public Map<String, String> getCatalogs() {
            return catalogs;
        }
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

        @JsonProperty("KeyManagerAlgorithm")
        private String keyManagerAlgorithm;

        @JsonProperty("KeyManagerProvider")
        private String keyManagerProvider;

        @JsonProperty("TrustManagerAlgorithm")
        private String trustManagerAlgorithm;

        @JsonProperty("TrustManagerProvider")
        private String trustManagerProvider;

        @JsonProperty("TrustStoreType")
        private String trustStoreType;

        @JsonProperty("TrustStoreProvider")
        private String trustStoreProvider;

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

        public String getKeyManagerAlgorithm() {
            return keyManagerAlgorithm;
        }

        public String getKeyManagerProvider() {
            return keyManagerProvider;
        }

        public String getTrustManagerAlgorithm() {
            return trustManagerAlgorithm;
        }

        public String getTrustManagerProvider() {
            return trustManagerProvider;
        }

        public String getTrustStoreType() {
            return trustStoreType;
        }

        public String getTrustStoreProvider() {
            return trustStoreProvider;
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
