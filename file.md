Файл: `java/src/main/java/ru/fedsfm/rfm/storage/RegistryStateStore.java`

Зачем: файловое хранилище состояния downloads/state.properties. Это позволяет не скачивать один и тот же реестр повторно.

```java
package ru.fedsfm.rfm.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.fedsfm.rfm.model.CatalogType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class RegistryStateStore {
private static final Logger log = LoggerFactory.getLogger(RegistryStateStore.class);

    private final Path path;

    public RegistryStateStore(Path path) {
        this.path = path;
    }

    public RegistryState load(CatalogType catalogType) {
        try {
            Properties properties = loadProperties();
            String prefix = catalogType.getCode() + ".";

            String idXml = properties.getProperty(prefix + "idXml");

            if (idXml == null || idXml.trim().isEmpty()) {
                log.info("No local state for catalog {}", catalogType.getCode());
                return null;
            }

            RegistryState state = new RegistryState(
                    idXml,
                    properties.getProperty(prefix + "date"),
                    properties.getProperty(prefix + "file"),
                    properties.getProperty(prefix + "downloadedAt")
            );

            log.info("Local state loaded. catalog={}, idXml={}, file={}",
                    catalogType.getCode(),
                    state.getIdXml(),
                    state.getFile());

            return state;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load registry state: " + path.toAbsolutePath(), e);
        }
    }

    public void save(CatalogType catalogType, RegistryState state) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());

            Properties properties = loadProperties();
            String prefix = catalogType.getCode() + ".";

            properties.setProperty(prefix + "idXml", nullToEmpty(state.getIdXml()));
            properties.setProperty(prefix + "date", nullToEmpty(state.getDate()));
            properties.setProperty(prefix + "file", nullToEmpty(state.getFile()));
            properties.setProperty(prefix + "downloadedAt", nullToEmpty(state.getDownloadedAt()));

            try (var output = Files.newOutputStream(path)) {
                properties.store(output, "RFM registry state");
            }

            log.info("Local state saved. catalog={}, idXml={}, file={}",
                    catalogType.getCode(),
                    state.getIdXml(),
                    state.getFile());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save registry state: " + path.toAbsolutePath(), e);
        }
    }

    private Properties loadProperties() throws IOException {
        Properties properties = new Properties();

        if (!Files.exists(path)) {
            return properties;
        }

        try (var input = Files.newInputStream(path)) {
            properties.load(input);
        }

        return properties;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
```
Файл: `java/src/main/java/ru/fedsfm/rfm/service/UpdateResult.java`

Зачем: явный результат операции обновления.

```java
package ru.fedsfm.rfm.service;

import java.nio.file.Path;

public final class UpdateResult {
private final boolean downloaded;
private final String idXml;
private final Path file;

    public UpdateResult(boolean downloaded, String idXml, Path file) {
        this.downloaded = downloaded;
        this.idXml = idXml;
        this.file = file;
    }

    public boolean isDownloaded() {
        return downloaded;
    }

    public String getIdXml() {
        return idXml;
    }

    public Path getFile() {
        return file;
    }
}
```
Файл: `java/src/main/java/ru/fedsfm/rfm/service/RegistryUpdateService.java`

Зачем: бизнес-логика проверки обновления и скачивания. API-клиент не знает про state и файлы, storage не знает про HTTP.

```java
package ru.fedsfm.rfm.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.fedsfm.rfm.client.RfmApiClient;
import ru.fedsfm.rfm.model.CatalogInfo;
import ru.fedsfm.rfm.model.CatalogType;
import ru.fedsfm.rfm.storage.RegistryState;
import ru.fedsfm.rfm.storage.RegistryStateStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class RegistryUpdateService {
private static final Logger log = LoggerFactory.getLogger(RegistryUpdateService.class);

    private final RfmApiClient apiClient;
    private final RegistryStateStore stateStore;
    private final Path outputDir;

    public RegistryUpdateService(
            RfmApiClient apiClient,
            RegistryStateStore stateStore,
            Path outputDir
    ) {
        this.apiClient = apiClient;
        this.stateStore = stateStore;
        this.outputDir = outputDir;
    }

    public UpdateResult update(CatalogType catalogType) {
        log.info("Checking registry update. catalog={}", catalogType.getCode());

        CatalogInfo remoteCatalog = apiClient.getCatalog(catalogType);
        String remoteIdXml = remoteCatalog.requireIdXml();

        RegistryState currentState = stateStore.load(catalogType);

        if (currentState != null && remoteIdXml.equalsIgnoreCase(currentState.getIdXml())) {
            log.info("Registry is already actual. catalog={}, idXml={}", catalogType.getCode(), remoteIdXml);

            Path currentFile = currentState.getFile() == null || currentState.getFile().trim().isEmpty()
                    ? null
                    : Path.of(currentState.getFile());

            return new UpdateResult(false, remoteIdXml, currentFile);
        }

        log.info("Registry update detected. catalog={}, oldIdXml={}, newIdXml={}",
                catalogType.getCode(),
                currentState == null ? "<none>" : currentState.getIdXml(),
                remoteIdXml);

        byte[] fileBytes = apiClient.downloadFile(catalogType, remoteIdXml);
        Path savedFile = saveFile(catalogType, remoteCatalog, fileBytes);

        RegistryState newState = new RegistryState(
                remoteIdXml,
                remoteCatalog.effectiveDate(),
                savedFile.toAbsolutePath().toString(),
                LocalDateTime.now().toString()
        );

        stateStore.save(catalogType, newState);

        log.info("Registry update completed. catalog={}, file={}", catalogType.getCode(), savedFile.toAbsolutePath());

        return new UpdateResult(true, remoteIdXml, savedFile);
    }

    private Path saveFile(CatalogType catalogType, CatalogInfo catalogInfo, byte[] fileBytes) {
        try {
            Path catalogDir = outputDir.resolve(catalogType.getCode());
            Files.createDirectories(catalogDir);

            Path target = catalogDir.resolve(buildFileName(catalogType, catalogInfo));
            Files.write(target, fileBytes);

            log.info("Registry file saved. path={}, bytes={}", target.toAbsolutePath(), fileBytes.length);

            return target;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save registry file", e);
        }
    }

    private String buildFileName(CatalogType catalogType, CatalogInfo catalogInfo) {
        String date = catalogInfo.effectiveDate();

        if (date == null || date.trim().isEmpty()) {
            date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        }

        String safeDate = date.replaceAll("[^0-9A-Za-z]+", "");
        String idXml = catalogInfo.requireIdXml();
        String shortId = idXml.length() > 8 ? idXml.substring(0, 8) : idXml;

        return catalogType.getFilePrefix()
                + "_"
                + safeDate
                + "_"
                + shortId
                + "."
                + catalogType.getExtension();
    }
}
```
Файл: `java/src/main/java/ru/fedsfm/rfm/Main.java`

Зачем: CLI-оркестратор. Только связывает зависимости, читает параметры запуска и выводит итог.

```java
package ru.fedsfm.rfm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.fedsfm.rfm.cert.CertificateLoader;
import ru.fedsfm.rfm.cert.ClientCertificate;
import ru.fedsfm.rfm.client.RfmApiClient;
import ru.fedsfm.rfm.client.RfmEndpoints;
import ru.fedsfm.rfm.client.RfmHttpClientFactory;
import ru.fedsfm.rfm.config.AppConfig;
import ru.fedsfm.rfm.config.ConfigLoader;
import ru.fedsfm.rfm.logging.Masking;
import ru.fedsfm.rfm.model.CatalogType;
import ru.fedsfm.rfm.service.RegistryUpdateService;
import ru.fedsfm.rfm.service.UpdateResult;
import ru.fedsfm.rfm.storage.RegistryStateStore;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            new Main().run(args);
        } catch (Exception e) {
            log.error("Application failed: {}", e.getMessage(), e);
            System.err.println("FAILED: " + e.getMessage());
            System.exit(1);
        }
    }

    private void run(String[] args) throws Exception {
        Cli cli = Cli.parse(args);

        Path configPath = cli.configPath != null ? cli.configPath : Path.of("..", "config.json");
        Path outputDir = cli.outputDir != null ? cli.outputDir : Path.of("downloads");

        Files.createDirectories(outputDir);

        ConfigLoader configLoader = new ConfigLoader();
        AppConfig config = configLoader.load(configPath);

        boolean production = cli.production != null ? cli.production : !config.isUseTestContour();

        CatalogType catalogType = cli.catalog != null
                ? CatalogType.from(cli.catalog)
                : CatalogType.from(configLoader.defaultCatalog(config));

        log.info("Application started");
        log.info("Config path: {}", configPath.toAbsolutePath());
        log.info("Output dir: {}", outputDir.toAbsolutePath());
        log.info("Contour: {}", production ? "prod" : "test");
        log.info("Catalog: {}", catalogType.getCode());
        log.info("Certificate serial: {}", Masking.serial(configLoader.certificateSerial(config)));

        ClientCertificate certificate = new CertificateLoader()
                .loadFromWindowsMy(configLoader.certificateSerial(config));

        HttpClient httpClient = new RfmHttpClientFactory().create(certificate);

        RfmApiClient apiClient = new RfmApiClient(
                httpClient,
                new RfmEndpoints(production)
        );

        apiClient.authenticate(
                configLoader.userName(config),
                configLoader.password(config)
        );

        RegistryUpdateService updateService = new RegistryUpdateService(
                apiClient,
                new RegistryStateStore(outputDir.resolve("state.properties")),
                outputDir
        );

        UpdateResult result = updateService.update(catalogType);

        if (result.isDownloaded()) {
            log.info("Update result: downloaded. idXml={}, file={}", result.getIdXml(), result.getFile());
            System.out.println("UPDATED " + result.getFile().toAbsolutePath());
        } else {
            log.info("Update result: no updates. idXml={}, file={}", result.getIdXml(), result.getFile());
            System.out.println("NO_UPDATES " + catalogType.getCode() + " " + result.getIdXml());

            if (result.getFile() != null) {
                System.out.println("CURRENT_FILE " + result.getFile().toAbsolutePath());
            }
        }
    }

    private static final class Cli {
        private Path configPath;
        private Path outputDir;
        private String catalog;
        private Boolean production;

        private static Cli parse(String[] args) {
            Cli cli = new Cli();

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];

                switch (arg) {
                    case "-c":
                    case "--config":
                        cli.configPath = Path.of(requireValue(args, ++i, arg));
                        break;
                    case "-o":
                    case "--out":
                        cli.outputDir = Path.of(requireValue(args, ++i, arg));
                        break;
                    case "-k":
                    case "--catalog":
                        cli.catalog = requireValue(args, ++i, arg);
                        break;
                    case "--prod":
                        cli.production = true;
                        break;
                    case "--test":
                        cli.production = false;
                        break;
                    case "-h":
                    case "--help":
                        printHelpAndExit();
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            return cli;
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("-")) {
                throw new IllegalArgumentException("Missing value for " + option);
            }

            return args[index];
        }

        private static void printHelpAndExit() {
            System.out.println("Usage:");
            System.out.println("  java -jar target/rfm-client-1.0.0.jar [options]");
            System.out.println();
            System.out.println("Options:");
            System.out.println("  -c, --config <path>    Path to config.json. Default: ../config.json");
            System.out.println("  -o, --out <dir>        Output directory. Default: downloads");
            System.out.println("  -k, --catalog <code>   te2, te21, mvk, un, un-rus");
            System.out.println("      --prod             Use production contour");
            System.out.println("      --test             Use test contour");
            System.out.println("  -h, --help             Show help");
            System.exit(0);
        }
    }
}
```
Как собирать

Из папки `java`:

```powershell
mvn clean package
```

Как запускать продуктивный перечень террористов/экстремистов v2.1

```powershell
java -jar target\rfm-client-1.0.0.jar --config ..\config.json --prod --catalog te21 --out downloads
```

Что будет создано

```text
java/downloads/state.properties
java/downloads/te21/suspect_<date>_<id>.zip
java/logs/rfm-client.log
```

Что важно

`cert.cer` не используется в этом варианте, потому что API требует клиентский сертификат для HTTPS-соединения. Для этого нужен сертификат с приватным ключом в Windows CurrentUser\My, а не только публичный .cer.

Если сертификат ГОСТ и обычный Java TLS не сможет с ним договориться, следующий шаг будет не переписывание архитектуры, а подключение CryptoPro JCP/JTLS provider в RfmHttpClientFactory. Архитектура выше для этого уже нормальная: менять придётся только слой `cert/client`, не весь проект.

## Подключение CryptoPro JCP/JTLS
```text
напиши на пока подключение CryptoPro чтобы была альтернатива
```

Да, добавим альтернативное подключение CryptoPro/JTLS, но сделаем его правильно: не завязываем весь проект на CryptoPro напрямую, а изолируем это в отдельном слое. Тогда обычный TLS-клиент остаётся, а CryptoPro включается конфигом.

Идея:

```text
RfmHttpClientFactory
если Certificate.UseCryptoPro = false -> обычный SSLContext TLS
если Certificate.UseCryptoPro = true  -> CryptoPro/JTLS SSLContext
Ниже что добавить и что заменить.
```

Обновить `config.json`

Зачем: дать возможность переключаться между обычным Java TLS и CryptoPro.

Добавь внутрь секции `Certificate`:

```json
"UseCryptoPro": true,
"CryptoPro": {
    "ProviderClasses": [
    "ru.CryptoPro.JCP.JCP",
    "ru.CryptoPro.Crypto.CryptoProvider",
    "ru.CryptoPro.ssl.Provider"
    ],
    "KeyStoreType": "Windows-MY",
    "KeyStoreProvider": "",
    "SslProtocol": "GostTLS",
    "SslProvider": "JTLS"
}
```
Итоговая секция будет примерно такой:

```json
"Certificate": {
    "SerialNumber": "40601d0005296a417b49c00c680a0452",
    "StoreLocation": "CurrentUser",
    "StoreName": "My",
    "CertFilePath": null,
    "CertPfxPath": null,
    "CertPfxPassword": "",
    "UseCryptoPro": true,
    "CryptoPro": {
    "ProviderClasses": [
    "ru.CryptoPro.JCP.JCP",
    "ru.CryptoPro.Crypto.CryptoProvider",
    "ru.CryptoPro.ssl.Provider"
    ],
    "KeyStoreType": "Windows-MY",
    "KeyStoreProvider": "",
    "SslProtocol": "GostTLS",
    "SslProvider": "JTLS"
    }
}
```
Если `GostTLS/JTLS` на твоей версии CryptoPro называются иначе, менять нужно будет только эти поля конфига.

Заменить файл: `java/src/main/java/ru/fedsfm/rfm/config/AppConfig.java`

Зачем: добавить настройки CryptoPro в типизированный конфиг.

<details>
<summary>java/src/main/java/ru/fedsfm/rfm/config/AppConfig.java</summary>

```java
package ru.fedsfm.rfm.config;

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
```
</details>
Добавить файл: `java/src/main/java/ru/fedsfm/rfm/crypto/CryptoProProviderRegistrar.java`

Зачем: регистрировать CryptoPro providers без compile-time зависимости от CryptoPro jar. То есть проект компилируется Maven-ом даже без CryptoPro, а CryptoPro нужен только в runtime classpath, если включён `UseCryptoPro`.

```java
package ru.fedsfm.rfm.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.fedsfm.rfm.exception.RfmCertificateException;

import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;

public final class CryptoProProviderRegistrar {
private static final Logger log = LoggerFactory.getLogger(CryptoProProviderRegistrar.class);

    private static final String[] DEFAULT_PROVIDER_CLASSES = {
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

        if (loaded.isEmpty()) {
            throw new RfmCertificateException(
                    "No CryptoPro providers were registered. Failed classes: " + failed
            );
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
```
Добавить файл: `java/src/main/java/ru/fedsfm/rfm/cert/CryptoProCertificateLoader.java`

Зачем: альтернативная загрузка сертификата через CryptoPro provider/key store. По умолчанию можно оставить Windows-MY, но если понадобится контейнер CryptoPro, меняется только `KeyStoreType`/`KeyStoreProvider` в конфиге.

```java
package ru.fedsfm.rfm.cert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.fedsfm.rfm.config.AppConfig;
import ru.fedsfm.rfm.crypto.CryptoProProviderRegistrar;
import ru.fedsfm.rfm.exception.RfmCertificateException;
import ru.fedsfm.rfm.logging.Masking;

import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.Locale;

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
```
Заменить файл: `java/src/main/java/ru/fedsfm/rfm/client/RfmHttpClientFactory.java`

Зачем: фабрика теперь умеет два режима: обычный TLS и CryptoPro/JTLS.

```java
package ru.fedsfm.rfm.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.fedsfm.rfm.cert.CertificateKeyManager;
import ru.fedsfm.rfm.cert.ClientCertificate;
import ru.fedsfm.rfm.config.AppConfig;
import ru.fedsfm.rfm.exception.RfmCertificateException;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.time.Duration;

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
                    sslProvider
            );

            return buildHttpClient(sslContext);
        } catch (Exception e) {
            throw new RfmCertificateException("Failed to create CryptoPro/JTLS HTTP client", e);
        }
    }

    private SSLContext createSslContext(
            ClientCertificate certificate,
            String sslProtocol,
            String sslProvider
    ) throws Exception {
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm()
        );

        keyManagerFactory.init(certificate.getKeyStore(), new char[0]);

        X509ExtendedKeyManager originalKeyManager = extractX509KeyManager(keyManagerFactory);
        CertificateKeyManager fixedAliasKeyManager = new CertificateKeyManager(
                originalKeyManager,
                certificate.getAlias()
        );

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
        );

        trustManagerFactory.init((KeyStore) null);

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
}
```
Заменить кусок в `Main.java`

Зачем: выбирать загрузчик сертификата и фабрику HTTP-клиента в зависимости от `UseCryptoPro`.

В `Main.java` добавь import:

```java
import ru.fedsfm.rfm.cert.CryptoProCertificateLoader;
```
Затем замени блок:

```java
ClientCertificate certificate = new CertificateLoader()
.loadFromWindowsMy(configLoader.certificateSerial(config));

HttpClient httpClient = new RfmHttpClientFactory().create(certificate);
```
на такой:

```java
ClientCertificate certificate;

if (config.getCertificate().isUseCryptoPro()) {
certificate = new CryptoProCertificateLoader()
.load(config.getCertificate(), configLoader.certificateSerial(config));
} else {
certificate = new CertificateLoader()
.loadFromWindowsMy(configLoader.certificateSerial(config));
}

HttpClient httpClient = new RfmHttpClientFactory()
.create(certificate, config.getCertificate());
```
Важно про запуск с CryptoPro

Так как мы не добавляем CryptoPro jar в Maven-зависимости, их надо дать в runtime classpath.

Пример запуска, если библиотеки лежат условно здесь:

```text
C:\Program Files\Crypto Pro\JCP\lib\
```
Команда:

```powershell
java -cp "target\rfm-client-1.0.0.jar;C:\Program Files\Crypto Pro\JCP\lib\*" ru.fedsfm.rfm.Main --config ..\config.json --prod --catalog te21 --out downloads
```
Если после этого будет ошибка вида:

```text
No such algorithm: GostTLS
```
или:

```text
No such provider: JTLS
```
значит в твоей версии CryptoPro другие имена TLS provider/protocol. Тогда меняются только эти поля:

```json
"SslProtocol": "GostTLS",
"SslProvider": "JTLS"
```
Если будет ошибка поиска ключевого контейнера/сертификата, тогда меняются:

```json
"KeyStoreType": "Windows-MY",
"KeyStoreProvider": ""
```
Например для некоторых установок CryptoPro может понадобиться не Windows store, а CryptoPro key store. Архитектурно мы это уже предусмотрели через `KeyStoreType` и `KeyStoreProvider`.