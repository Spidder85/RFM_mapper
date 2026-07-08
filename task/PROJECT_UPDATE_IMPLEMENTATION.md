# Инструкция по актуальным доработкам

Дата актуализации: 2026-07-08.

`rfm-downloader` в целом протестирован и рабочий. Ниже оставлены только текущие вопросы и доработки:

1. Одно итоговое уведомление вместо нескольких писем при обновлении нескольких реестров.
2. Защита новой схемы `OutputDirectory` от `null` и неполного конфига.
3. Публикация события для клиентских Zenith не только в `IMPORT_ONLY`, но и в `FULL`.
4. Проверка упаковки рабочих конфигов.
5. Проверка/доработка ротации логов, если проблема повторится.

Код проекта автоматически не менялся. Ниже приведено, что и куда писать вручную.

Корень проекта:

```text
G:\tmp\fedfsm\java
```

---

# 1. Одно уведомление по всем обновленным реестрам

## Проблема

Сейчас при запуске нескольких каталогов:

```json
"Catalogs": [
  "te21",
  "un",
  "mvk"
]
```

если обновились несколько реестров, `rfm-downloader` отправляет несколько отдельных уведомлений.

Нужно одно письмо/сообщение за запуск программы:

```text
Обновлены перечни Росфинмониторинга

1. Террористы и экстремисты
   ...

2. Перечень ООН
   ...

3. Решения МВК
   ...
```

## Решение

Добавляем маленький DTO `RegistryNotificationItem`, меняем `UnifiedNotificationTextBuilder`, затем меняем `Main`, чтобы уведомление отправлялось один раз после цикла обработки каталогов.

---

## 1.1. Создать RegistryNotificationItem

### Файл

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/service/RegistryNotificationItem.java
```

### Код

```java
package org.ikozmin.rfm.service;

import org.ikozmin.common.event.ZenithProcessingSummary;

public record RegistryNotificationItem(
        UpdateResult result,
        ZenithProcessingSummary zenithSummary
) {
}
```

---

## 1.2. Заменить UnifiedNotificationTextBuilder полностью

### Файл

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/service/UnifiedNotificationTextBuilder.java
```

### Заменить полностью

```java
package org.ikozmin.rfm.service;

import org.ikozmin.common.event.ZenithProcessingSummary;
import org.ikozmin.common.notification.NotificationMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class UnifiedNotificationTextBuilder {
    public NotificationMessage build(List<RegistryNotificationItem> items) throws Exception {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Notification items are empty");
        }

        String subject = items.size() == 1
                ? "Обновлен перечень Росфинмониторинга: " + displayCatalogName(items.get(0).result().catalogType().getCode())
                : "Обновлены перечни Росфинмониторинга: " + items.size();

        String indent = "    ";
        StringBuilder body = new StringBuilder();

        body.append("Здравствуйте.").append(System.lineSeparator());
        body.append(System.lineSeparator());
        body.append("В системе Росфинмониторинга опубликованы обновления перечней.").append(System.lineSeparator());
        body.append("Файлы успешно загружены.").append(System.lineSeparator());
        body.append(System.lineSeparator());
        body.append("Дата проверки: ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")))
                .append(System.lineSeparator());
        body.append(System.lineSeparator());

        for (int i = 0; i < items.size(); i++) {
            RegistryNotificationItem item = items.get(i);
            UpdateResult result = item.result();

            body.append(i + 1)
                    .append(". ")
                    .append(displayCatalogName(result.catalogType().getCode()))
                    .append(System.lineSeparator());

            body.append(indent).append("Предыдущий idXml: ")
                    .append(result.oldIdXml() == null ? "отсутствует" : result.oldIdXml())
                    .append(System.lineSeparator());

            body.append(indent).append("Новый idXml: ")
                    .append(result.idXml())
                    .append(System.lineSeparator());

            if (result.file() != null) {
                body.append(indent).append("Файл для обработки: ")
                        .append(result.file().toAbsolutePath())
                        .append(System.lineSeparator());

                if (Files.exists(result.file())) {
                    body.append(indent).append("Размер файла: ")
                            .append(formatFileSize(Files.size(result.file())))
                            .append(System.lineSeparator());
                }
            }

            if (result.sha256() != null && !result.sha256().isBlank()) {
                body.append(indent).append("SHA-256 архива: ")
                        .append(result.sha256())
                        .append(System.lineSeparator());
            }

            appendZenithBlock(body, indent, item.zenithSummary());
            body.append(System.lineSeparator());
        }

        body.append("Это автоматическое уведомление. Не надо на него отвечать.").append(System.lineSeparator());

        return new NotificationMessage(subject, body.toString());
    }

    public NotificationMessage build(
            String catalogType,
            String idXml,
            String oldIdXml,
            Path filePath,
            String checksum,
            ZenithProcessingSummary zenithSummary
    ) throws Exception {
        UpdateResult result = new UpdateResult(
                true,
                org.ikozmin.rfm.model.CatalogType.from(catalogType),
                oldIdXml,
                idXml,
                filePath,
                filePath,
                checksum,
                safeFileSize(filePath),
                LocalDateTime.now().toString()
        );

        return build(List.of(new RegistryNotificationItem(result, zenithSummary)));
    }

    private void appendZenithBlock(StringBuilder body, String indent, ZenithProcessingSummary zenithSummary) {
        body.append(indent).append("Проверка в Zenith:").append(System.lineSeparator());

        if (zenithSummary == null) {
            body.append(indent).append(indent)
                    .append("Результат Zenith недоступен. Проверьте журнал zenith-processor и каталог events.")
                    .append(System.lineSeparator());
            return;
        }

        if (zenithSummary.newPersons() == 0) {
            body.append(indent).append(indent)
                    .append("Новых лиц не найдено.")
                    .append(System.lineSeparator());
            body.append(indent).append(indent)
                    .append("Всего совпадений в отчете: ")
                    .append(zenithSummary.totalPersons())
                    .append(System.lineSeparator());
            return;
        }

        body.append(indent).append(indent)
                .append("Найдены новые лица: ")
                .append(zenithSummary.newPersons())
                .append(System.lineSeparator());

        int index = 1;
        for (ZenithProcessingSummary.Person person : zenithSummary.persons()) {
            body.append(indent).append(indent)
                    .append(index++)
                    .append(". ")
                    .append(blankToDash(person.displayName()))
                    .append(System.lineSeparator());
            body.append(indent).append(indent).append(indent)
                    .append("Номер счета: ")
                    .append(blankToDash(person.accountNumber()))
                    .append(System.lineSeparator());
            body.append(indent).append(indent).append(indent)
                    .append("Организация: ")
                    .append(blankToDash(person.emitentName()))
                    .append(System.lineSeparator());
            body.append(indent).append(indent).append(indent)
                    .append("Черновики ФЭС: ")
                    .append(person.packageDirectory() == null ? "-" : person.packageDirectory())
                    .append(System.lineSeparator());
        }

        body.append(indent).append(indent)
                .append("Автоматическая отправка в Росфинмониторинг не выполнялась.")
                .append(System.lineSeparator());
        body.append(indent).append(indent)
                .append("Необходимо проверить подготовленные черновики и принять решение вручную.")
                .append(System.lineSeparator());
    }

    private long safeFileSize(Path file) {
        if (file == null || !Files.exists(file)) {
            return 0L;
        }

        try {
            return Files.size(file);
        } catch (Exception e) {
            return 0L;
        }
    }

    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " байт";
        }

        if (size < 1024 * 1024) {
            return String.format("%.2f КБ", size / 1024.0);
        }

        if (size < 1024 * 1024 * 1024) {
            return String.format("%.2f МБ", size / (1024.0 * 1024));
        }

        return String.format("%.2f ГБ", size / (1024.0 * 1024 * 1024));
    }

    private String displayCatalogName(String catalogType) {
        if (catalogType == null) {
            return "Неизвестный перечень";
        }

        return switch (catalogType.toLowerCase()) {
            case "te2", "te21" -> "Террористы и экстремисты";
            case "mvk" -> "Решения МВК";
            case "un" -> "Перечень ООН";
            case "un-rus" -> "Перечень ООН на русском языке";
            default -> catalogType;
        };
    }

    private String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
```

---

## 1.3. Заменить Main полностью

### Зачем

В текущем `Main` уведомление отправляется внутри цикла по каталогам:

```java
sendNotificationIfNeeded(config, result, zenithSummary.orElse(null));
```

Из-за этого при трех обновленных реестрах будет три письма.

Новый вариант собирает список `RegistryNotificationItem`, а после завершения цикла отправляет одно уведомление.

Также новый вариант аккуратнее работает с `OutputDirectory`, если часть настроек отсутствует.

### Файл

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/Main.java
```

### Заменить полностью

```java
package org.ikozmin.rfm;

import org.ikozmin.common.event.ProcessingSummaryStore;
import org.ikozmin.common.event.ZenithProcessingSummary;
import org.ikozmin.common.notification.NotificationMessage;
import org.ikozmin.rfm.audit.AuditWriter;
import org.ikozmin.rfm.cert.CertificateLoader;
import org.ikozmin.rfm.cert.ClientCertificate;
import org.ikozmin.rfm.cert.CryptoProCertificateLoader;
import org.ikozmin.rfm.client.RetryPolicy;
import org.ikozmin.rfm.client.RfmApiClient;
import org.ikozmin.rfm.client.RfmEndpoints;
import org.ikozmin.rfm.client.RfmHttpClientFactory;
import org.ikozmin.rfm.config.AppConfig;
import org.ikozmin.rfm.config.ConfigLoader;
import org.ikozmin.rfm.event.PublishedRegistryEvent;
import org.ikozmin.rfm.event.RegistryEventService;
import org.ikozmin.rfm.logging.Masking;
import org.ikozmin.rfm.model.CatalogType;
import org.ikozmin.rfm.model.Contour;
import org.ikozmin.rfm.service.EventRetentionService;
import org.ikozmin.rfm.service.NotificationService;
import org.ikozmin.rfm.service.RegistryNotificationItem;
import org.ikozmin.rfm.service.RegistryUpdateService;
import org.ikozmin.rfm.service.RetentionService;
import org.ikozmin.rfm.service.UnifiedNotificationTextBuilder;
import org.ikozmin.rfm.service.UpdateResult;
import org.ikozmin.rfm.storage.RegistryStateStore;
import org.ikozmin.rfm.trigger.ZenithProcessorTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(
        name = "rfm-client",
        mixinStandardHelpOptions = true,
        description = "Downloads Rosfinmonitoring registry updates"
)
public final class Main implements Callable<Integer> {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @Option(names = {"-c", "--config"}, description = "Path to config.json")
    private Path configPath = Path.of("config", "config.json");

    @Option(names = {"-k", "--catalog"}, description = "Catalog: te2, te21, mvk, un, un-rus")
    private String catalog;

    @Option(names = "--prod", description = "Use production contour")
    private boolean prod;

    @Option(names = "--test", description = "Use test contour")
    private boolean test;

    @Option(names = "--contour", description = "Contour: prod or test")
    private String contourValue;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        try {
            run();
            return 0;
        } catch (Exception e) {
            ExitCode exitCode = ExitCode.from(e);
            log.error("Application failed. exitCode={}, error={}", exitCode, e.getMessage(), e);
            System.err.println("Application failed: " + e.getMessage());
            return exitCode.code();
        }
    }

    private void run() throws Exception {
        ConfigLoader configLoader = new ConfigLoader();
        AppConfig config = configLoader.load(configPath);

        Path workDir = Path.of("downloads");
        Files.createDirectories(workDir);

        Path downloadDir = resolveDownloadDir(config);
        Files.createDirectories(downloadDir);

        Map<String, String> catalogMapping = resolveCatalogFolderMapping(config);
        createCatalogDirectories(downloadDir, catalogMapping);

        Contour contour = resolveContour(config);
        List<CatalogType> catalogTypes = resolveCatalogs(config, configLoader);

        log.info("Application start");
        log.info("Config path: {}", configPath.toAbsolutePath());
        log.info("Work directory: {}", workDir.toAbsolutePath());
        log.info("Download directory: {}", downloadDir.toAbsolutePath());
        log.info("Contour: {}", contour);
        log.info("Catalogs: {}", catalogTypes.stream().map(CatalogType::getCode).toList());
        log.info("Certificate serial: {}", Masking.serial(configLoader.certificateSerial(config)));

        ClientCertificate certificate;

        if (config.getCertificate().isUseCryptoPro()) {
            certificate = new CryptoProCertificateLoader()
                    .load(config.getCertificate(), configLoader.certificateSerial(config));
        } else {
            certificate = new CertificateLoader()
                    .loadFromWindowsMy(configLoader.certificateSerial(config));
        }

        RfmHttpClientFactory factory = new RfmHttpClientFactory();
        HttpClient httpClient = factory.create(certificate, config.getCertificate());

        AuditWriter auditWriter = new AuditWriter(workDir.resolve("audit"));

        RfmApiClient apiClient = new RfmApiClient(
                httpClient,
                factory.getSslContext(),
                new RfmEndpoints(contour),
                auditWriter
        );

        RetryPolicy retryPolicy = new RetryPolicy(3, Duration.ofSeconds(2));

        retryPolicy.executeVoid("authenticate", () -> apiClient.authenticate(
                configLoader.userName(config),
                configLoader.password(config)
        ));

        RegistryUpdateService updateService = new RegistryUpdateService(
                apiClient,
                new RegistryStateStore(workDir.resolve("state.properties")),
                workDir,
                downloadDir,
                catalogMapping
        );

        List<RegistryNotificationItem> notificationItems = new ArrayList<>();

        for (CatalogType catalogType : catalogTypes) {
            UpdateResult result = retryPolicy.execute(
                    "registry-update-" + catalogType.getCode(),
                    () -> updateService.update(catalogType)
            );

            if (result.isDownloaded()) {
                log.info("Update result: downloaded. catalog={}, oldIdXml={}, newIdXml={}, file={}, sha256={}, fileSize={}",
                        result.catalogType().getCode(),
                        Masking.id(result.oldIdXml()),
                        Masking.id(result.idXml()),
                        result.file(),
                        result.sha256(),
                        result.fileSize());

                PublishedRegistryEvent event = publishRegistryEvent(config, result);
                runZenithProcessorIfNeeded(config, event.file());

                Optional<ZenithProcessingSummary> zenithSummary = loadZenithSummary(config, event.eventId());
                notificationItems.add(new RegistryNotificationItem(result, zenithSummary.orElse(null)));

                System.out.println("UPDATED " + result.catalogType().getCode() + " " + result.file().toAbsolutePath());
            } else {
                log.info("Update result: no updates. catalog={}, idXml={}, file={}",
                        result.catalogType().getCode(),
                        Masking.id(result.idXml()),
                        result.file());

                System.out.println("NO_UPDATES " + result.catalogType().getCode() + " " + result.idXml());

                if (result.file() != null) {
                    System.out.println("CURRENT_FILE " + result.catalogType().getCode() + " " + result.file().toAbsolutePath());
                }
            }
        }

        sendNotificationIfNeeded(config, notificationItems);
        applyRetentionIfNeeded(config, workDir, downloadDir, catalogTypes);
    }

    private PublishedRegistryEvent publishRegistryEvent(AppConfig config, UpdateResult result) {
        Path eventRootDir = Path.of(config.getEvents() == null
                ? "events/registry-updated"
                : config.getEvents().getDirectory()
        );

        RegistryEventService eventService = new RegistryEventService(eventRootDir);
        PublishedRegistryEvent publishedEvent = eventService.publish(result);

        log.info("Registry update event published: eventId={}, file={}",
                publishedEvent.eventId(),
                publishedEvent.file().toAbsolutePath());

        return publishedEvent;
    }

    private void runZenithProcessorIfNeeded(AppConfig config, Path eventFile) {
        ZenithProcessorTrigger trigger = new ZenithProcessorTrigger(config.getZenithTrigger());

        if (!trigger.isEnabled()) {
            log.info("Zenith trigger is disabled");
            return;
        }

        log.info("Zenith trigger enabled. eventFile={}", eventFile.toAbsolutePath());
        trigger.runOnce();
    }

    private Path resolveDownloadDir(AppConfig config) {
        AppConfig.OutputConfig output = config.getOutputDirectory();

        if (output == null || output.getPath() == null || output.getPath().trim().isEmpty()) {
            log.warn("OutputDirectory.Path is not configured, using default: downloads");
            return Path.of("downloads");
        }

        return Path.of(output.getPath().trim());
    }

    private Map<String, String> resolveCatalogFolderMapping(AppConfig config) {
        AppConfig.OutputConfig output = config.getOutputDirectory();

        if (output == null || output.getCatalogs() == null) {
            return Map.of();
        }

        return output.getCatalogs();
    }

    private void createCatalogDirectories(Path downloadDir, Map<String, String> catalogMapping) throws Exception {
        for (String folderName : catalogMapping.values()) {
            if (folderName != null && !folderName.isBlank()) {
                Files.createDirectories(downloadDir.resolve(folderName));
            }
        }
    }

    private void sendNotificationIfNeeded(AppConfig config, List<RegistryNotificationItem> notificationItems) {
        if (notificationItems == null || notificationItems.isEmpty()) {
            return;
        }

        NotificationService notificationService = new NotificationService(config.getNotifications());

        if (!notificationService.isEnabled()) {
            return;
        }

        try {
            UnifiedNotificationTextBuilder builder = new UnifiedNotificationTextBuilder();
            NotificationMessage message = builder.build(notificationItems);
            notificationService.send(message);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build or send notification", e);
        }
    }

    private void applyRetentionIfNeeded(AppConfig config, Path workDir, Path downloadDir, List<CatalogType> catalogTypes) {
        RetentionService retentionService = new RetentionService(config.getRetention());

        if (!retentionService.isEnabled()) {
            return;
        }

        for (CatalogType catalogType : catalogTypes) {
            retentionService.apply(workDir, downloadDir, catalogType);
        }

        Path eventRootDir = Path.of(config.getEvents() == null
                ? "events/registry-updated"
                : config.getEvents().getDirectory()
        );

        new EventRetentionService(config.getRetention()).apply(eventRootDir);
    }

    private Optional<ZenithProcessingSummary> loadZenithSummary(AppConfig config, String eventId) {
        Path eventRootDir = Path.of(config.getEvents() == null
                ? "events/registry-updated"
                : config.getEvents().getDirectory()
        );

        Path summaryDir = eventRootDir.resolve("results");

        return new ProcessingSummaryStore(summaryDir).load(eventId);
    }

    private List<CatalogType> resolveCatalogs(AppConfig config, ConfigLoader configLoader) {
        if (catalog != null && !catalog.isBlank()) {
            return List.of(CatalogType.from(catalog));
        }

        if (!config.getCatalogs().isEmpty()) {
            return config.getCatalogs()
                    .stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(CatalogType::from)
                    .distinct()
                    .toList();
        }

        return List.of(CatalogType.from(configLoader.defaultCatalog(config)));
    }

    private Contour resolveContour(AppConfig config) {
        int specified = 0;

        if (prod) {
            specified++;
        }

        if (test) {
            specified++;
        }

        if (contourValue != null && !contourValue.isBlank()) {
            specified++;
        }

        if (specified > 1) {
            throw new IllegalArgumentException("Use only one contour option: --prod, --test or --contour");
        }

        if (prod) {
            return Contour.PROD;
        }

        if (test) {
            return Contour.TEST;
        }

        if (contourValue != null && !contourValue.isBlank()) {
            return Contour.fromCliValue(contourValue);
        }

        return Contour.from(config.isUseTestContour());
    }
}
```

---

# 2. Устойчивость OutputDirectory и раздельных папок

## Текущее состояние

В `config.template.json` уже добавлена структура:

```json
"OutputDirectory": {
  "Path": "\\\\t-cent\\Zen-co\\terrlist\\test\\",
  "Catalogs": {
    "te21": "терр_экстр",
    "un": "Санкционные_списки_ООН",
    "mvk": "Перечни_решМКО(МВК)"
  }
}
```

В `RegistryUpdateService` уже есть сохранение в:

```text
<OutputDirectory.Path>/<Catalogs[catalog]>/<date>/
```

## Что дополнительно проверить

После загрузки должны получаться такие папки:

```text
\\t-cent\Zen-co\terrlist\test\терр_экстр\<дата>\
\\t-cent\Zen-co\terrlist\test\Санкционные_списки_ООН\<дата>\
\\t-cent\Zen-co\terrlist\test\Перечни_решМКО(МВК)\<дата>\
```

Если для какого-то каталога нет mapping, код должен использовать папку по коду каталога:

```text
te21
un
mvk
```

## Рекомендация

Отдельно `RegistryUpdateService` сейчас менять не обязательно. Основной риск был в `Main`, где `config.getOutputDirectory().getCatalogs()` мог упасть при неполном конфиге. В полном коде `Main` выше это исправлено через:

```java
private Map<String, String> resolveCatalogFolderMapping(AppConfig config)
```

---

# 3. Zenith: создавать событие для офисов и в FULL, и в IMPORT_ONLY

## Проблема

Сейчас в `ZenithWorkflowService.processImportOnly(...)` событие для клиентских офисов создается:

```java
new ZenithImportEventPublisher(config.getEvents()).publish(event);
```

А в `processFull(...)` эта строка закомментирована.

Если центральный запуск будет идти в режиме:

```bat
run-zenith-once.bat --mode FULL
```

то офисные клиенты не получат событие на запуск своей проверки.

## Решение

В `FULL` нужно тоже публиковать событие после успешного импорта.

## Файл

```text
zenith-processor/src/main/java/org/ikozmin/zenith/service/ZenithWorkflowService.java
```

## Что заменить

В методе `processFull` сейчас есть блок:

```java
importPersonListIfEnabled(event);
runMassCheckIfEnabled(event.catalog());

// возможно лишнее, но на всякий случай ставлю генерацию сообытия
//new ZenithImportEventPublisher(config.getEvents()).publish(event);
```

Заменить на:

```java
importPersonListIfEnabled(event);
new ZenithImportEventPublisher(config.getEvents()).publish(event);
runMassCheckIfEnabled(event.catalog());
```

## Почему именно так

Порядок должен быть таким:

1. Импортировать новый реестр в центральный Zenith.
2. Создать события для офисных клиентских Zenith.
3. Выполнить локальный полный цикл, если центральному Zenith тоже нужен отчет.

Если офисные события создавать после отчета, то филиалы будут ждать дольше без необходимости.

---

# 4. Сетевые папки офисов для клиентских Zenith

## Как должно быть на центральном Zenith

В центральном:

```text
zenith-processor/config/zenith-config.json
```

указать список папок офисов:

```json
"Events": {
  "RegistryUpdatedDirectory": "events/registry-updated",
  "ImportCompletedDirectories": [
    "\\\\office1-server\\rfm-events\\zenith-check",
    "\\\\office2-server\\rfm-events\\zenith-check",
    "\\\\office3-server\\rfm-events\\zenith-check",
    "\\\\office4-server\\rfm-events\\zenith-check"
  ],
  "CheckDirectory": "events/zenith-imported/local"
}
```

`ZenithImportEventPublisher` сам добавит подпапку:

```text
new
```

И события будут созданы так:

```text
\\office1-server\rfm-events\zenith-check\new\<event>.json
\\office2-server\rfm-events\zenith-check\new\<event>.json
\\office3-server\rfm-events\zenith-check\new\<event>.json
\\office4-server\rfm-events\zenith-check\new\<event>.json
```

## Как должно быть в офисе

В офисном:

```text
zenith-processor/config/zenith-config.json
```

указать только свою очередь:

```json
"Events": {
  "CheckDirectory": "\\\\office1-server\\rfm-events\\zenith-check"
}
```

Важно: указывать корень очереди, не `new`.

Правильно:

```text
\\office1-server\rfm-events\zenith-check
```

Неправильно:

```text
\\office1-server\rfm-events\zenith-check\new
```

Потому что consumer сам ожидает структуру:

```text
zenith-check/
  new/
  processing/
  processed/
  failed/
```

---

# 5. Уведомления после проверки Zenith

## Важное решение, чтобы не было дублей

Уведомление должно быть единым, но источник уведомления зависит от сценария запуска.

### Центральный запуск через rfm-downloader

Если `rfm-downloader` скачал реестр, запустил Zenith и прочитал `ZenithProcessingSummary`, то итоговое уведомление отправляет только:

```text
rfm-downloader
```

В этом случае в центральном `zenith-config.json` уведомления нужно выключить:

```json
"Notifications": {
  "Enabled": false
}
```

Иначе при `FULL` будет два уведомления:

```text
1. от rfm-downloader
2. от zenith-processor
```

### Офисный запуск Zenith

Если в офисе запускается только:

```bat
run-zenith-drain.bat --mode CHECK_ONLY
```

то `rfm-downloader` там не работает и отправить результат проверки некому. В этом сценарии уведомление отправляет:

```text
zenith-processor
```

В офисном `zenith-config.json` уведомления включаются:

```json
"Notifications": {
  "Enabled": true,
  ...
}
```

Итог:

```text
центральный FULL от rfm-downloader -> уведомляет rfm-downloader
офисный CHECK_ONLY                 -> уведомляет zenith-processor
```

---

## 5.1. Перенести конфиги уведомлений в common

Сейчас `EmailConfig`, `TelegramConfig`, `NotificationsConfig` лежат в `rfm-downloader`.

Чтобы ими пользовался и `rfm-downloader`, и `zenith-processor`, перенести их в `common`.

### Создать файл

```text
common/src/main/java/org/ikozmin/common/notification/NotificationsConfig.java
```

### Код

```java
package org.ikozmin.common.notification;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class NotificationsConfig {
    @JsonProperty("Enabled")
    private boolean enabled;

    @JsonProperty("Email")
    private EmailConfig email;

    @JsonProperty("Telegram")
    private TelegramConfig telegram;

    public boolean isEnabled() {
        return enabled;
    }

    public EmailConfig getEmail() {
        return email;
    }

    public TelegramConfig getTelegram() {
        return telegram;
    }
}
```

### Создать файл

```text
common/src/main/java/org/ikozmin/common/notification/EmailConfig.java
```

### Код

```java
package org.ikozmin.common.notification;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class EmailConfig {
    @JsonProperty("Enabled")
    private boolean enabled;

    @JsonProperty("SmtpHost")
    private String smtpHost;

    @JsonProperty("SmtpPort")
    private Integer smtpPort;

    @JsonProperty("SmtpUsername")
    private String smtpUsername;

    @JsonProperty("SmtpPassword")
    private String smtpPassword;

    @JsonProperty("UseTls")
    private boolean useTls;

    @JsonProperty("From")
    private String from;

    @JsonProperty("To")
    private List<String> to;

    @JsonProperty("Subject")
    private String subject;

    @JsonProperty("IncludeAttachment")
    private boolean includeAttachment;

    public boolean isEnabled() {
        return enabled;
    }

    public String getSmtpHost() {
        return smtpHost;
    }

    public int getSmtpPort() {
        return smtpPort == null ? 25 : smtpPort;
    }

    public String getSmtpUsername() {
        return smtpUsername;
    }

    public String getSmtpPassword() {
        return smtpPassword;
    }

    public boolean isUseTls() {
        return useTls;
    }

    public String getFrom() {
        return from;
    }

    public List<String> getTo() {
        return to;
    }

    public String getSubject() {
        return subject;
    }

    public boolean isIncludeAttachment() {
        return includeAttachment;
    }
}
```

### Создать файл

```text
common/src/main/java/org/ikozmin/common/notification/TelegramConfig.java
```

### Код

```java
package org.ikozmin.common.notification;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class TelegramConfig {
    @JsonProperty("Enabled")
    private boolean enabled;

    @JsonProperty("Token")
    private String token;

    @JsonProperty("ChatIds")
    private List<String> chatIds;

    @JsonProperty("ApiIp")
    private String apiIp;

    public boolean isEnabled() {
        return enabled;
    }

    public String getToken() {
        return token;
    }

    public List<String> getChatIds() {
        return chatIds;
    }

    public String getApiIp() {
        return apiIp;
    }
}
```

### После переноса

Старые файлы удалить:

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/config/NotificationsConfig.java
rfm-downloader/src/main/java/org/ikozmin/rfm/config/EmailConfig.java
rfm-downloader/src/main/java/org/ikozmin/rfm/config/TelegramConfig.java
```

В `AppConfig` заменить импорт/тип `NotificationsConfig` на:

```java
import org.ikozmin.common.notification.NotificationsConfig;
```

---

## 5.2. Перенести отправители уведомлений в common

### common/pom.xml

В `common/pom.xml` добавить зависимость Jakarta Mail:

```xml
<dependency>
    <groupId>com.sun.mail</groupId>
    <artifactId>jakarta.mail</artifactId>
    <version>2.0.1</version>
</dependency>
```

После переноса из `rfm-downloader/pom.xml` зависимость `jakarta.mail` можно удалить, потому что она придет транзитивно через `common`.

### Создать файл

```text
common/src/main/java/org/ikozmin/common/notification/EmailNotificationSender.java
```

### Код

```java
package org.ikozmin.common.notification;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

public final class EmailNotificationSender implements NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(EmailNotificationSender.class);

    private final EmailConfig config;

    public EmailNotificationSender(EmailConfig config) {
        this.config = config;
    }

    @Override
    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    @Override
    public void send(NotificationMessage message) {
        if (!isEnabled()) {
            return;
        }

        try {
            validate();

            Path attachment = message.attachments().isEmpty()
                    ? null
                    : message.attachments().getFirst();

            sendEmail(message.subject(), message.body(), config.getTo(), attachment);

            log.info("Email notification sent. recipients={}", config.getTo().size());
        } catch (Exception e) {
            log.error("Failed to send email notification: {}", e.getMessage(), e);
        }
    }

    private void sendEmail(String subject, String body, List<String> recipients, Path filePath) throws Exception {
        Session session = createSession();

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(config.getFrom()));

        for (String recipient : recipients) {
            if (!isBlank(recipient)) {
                message.addRecipient(Message.RecipientType.TO, new InternetAddress(recipient.trim()));
            }
        }

        message.setSubject(subject, "UTF-8");

        if (config.isIncludeAttachment() && filePath != null && Files.exists(filePath)) {
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(body, "UTF-8");

            MimeBodyPart attachmentPart = new MimeBodyPart();
            attachmentPart.attachFile(filePath.toFile());

            Multipart multipart = new MimeMultipart();
            multipart.addBodyPart(textPart);
            multipart.addBodyPart(attachmentPart);

            message.setContent(multipart);
        } else {
            message.setText(body, "UTF-8");
        }

        Transport.send(message);
    }

    private Session createSession() {
        Properties props = new Properties();
        props.put("mail.smtp.host", config.getSmtpHost());
        props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
        props.put("mail.smtp.auth", String.valueOf(!isBlank(config.getSmtpUsername())));
        props.put("mail.smtp.starttls.enable", String.valueOf(config.isUseTls()));

        if (isBlank(config.getSmtpUsername())) {
            return Session.getInstance(props);
        }

        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.getSmtpUsername(), config.getSmtpPassword());
            }
        });
    }

    private void validate() {
        if (isBlank(config.getSmtpHost())) {
            throw new IllegalStateException("Notifications.Email.SmtpHost is empty");
        }

        if (isBlank(config.getFrom())) {
            throw new IllegalStateException("Notifications.Email.From is empty");
        }

        if (config.getTo() == null || config.getTo().isEmpty()) {
            throw new IllegalStateException("Notifications.Email.To is empty");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
```

### Создать файл

```text
common/src/main/java/org/ikozmin/common/notification/TelegramNotificationSender.java
```

### Код

```java
package org.ikozmin.common.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public final class TelegramNotificationSender implements NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationSender.class);
    private static final String DEFAULT_TELEGRAM_API_IP = "149.154.167.220";

    private final TelegramConfig config;

    public TelegramNotificationSender(TelegramConfig config) {
        this.config = config;
    }

    @Override
    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    @Override
    public void send(NotificationMessage message) {
        if (!isEnabled()) {
            return;
        }

        try {
            validate();

            for (String chatId : config.getChatIds()) {
                if (!isBlank(chatId)) {
                    sendMessage(chatId.trim(), message.body());
                }
            }

            log.info("Telegram notification sent. chats={}", config.getChatIds().size());
        } catch (Exception e) {
            log.error("Telegram notification failed: {}", e.getMessage(), e);
        }
    }

    private void sendMessage(String chatId, String text) throws Exception {
        String apiIp = isBlank(config.getApiIp()) ? DEFAULT_TELEGRAM_API_IP : config.getApiIp().trim();
        String url = "https://api.telegram.org/bot" + config.getToken() + "/sendMessage";

        String[] command = {
                "curl.exe",
                "--silent",
                "--show-error",
                "--resolve", "api.telegram.org:443:" + apiIp,
                "--request", "POST",
                url,
                "--data-urlencode", "chat_id=" + chatId,
                "--data-urlencode", "text=" + text,
                "--data", "disable_web_page_preview=true"
        };

        log.info("Sending Telegram message. chatId={}, apiIp={}, token={}",
                chatId,
                apiIp,
                maskToken(config.getToken()));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        int exitCode = process.waitFor();
        String response = output.toString();

        if (exitCode != 0 || !response.contains("\"ok\":true")) {
            throw new IllegalStateException("Telegram API call failed. exitCode=" + exitCode + ", response=" + response);
        }

        log.info("Telegram message delivered. chatId={}", chatId);
    }

    private void validate() {
        if (isBlank(config.getToken())) {
            throw new IllegalStateException("Notifications.Telegram.Token is empty");
        }

        if (config.getChatIds() == null || config.getChatIds().isEmpty()) {
            throw new IllegalStateException("Notifications.Telegram.ChatIds is empty");
        }
    }

    private String maskToken(String token) {
        if (token == null || token.length() < 10) {
            return "***";
        }

        return token.substring(0, 4) + "***" + token.substring(token.length() - 4);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
```

### Создать файл

```text
common/src/main/java/org/ikozmin/common/notification/NotificationDispatcher.java
```

### Код

```java
package org.ikozmin.common.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationsConfig config;
    private final List<NotificationSender> senders;

    public NotificationDispatcher(NotificationsConfig config) {
        this.config = config;

        if (config == null) {
            this.senders = List.of();
        } else {
            this.senders = List.of(
                    new EmailNotificationSender(config.getEmail()),
                    new TelegramNotificationSender(config.getTelegram())
            );
        }
    }

    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    public void send(NotificationMessage message) {
        if (!isEnabled()) {
            return;
        }

        long enabledSenders = 0;

        for (NotificationSender sender : senders) {
            if (sender.isEnabled()) {
                sender.send(message);
                enabledSenders++;
            }
        }

        log.info("Notification dispatch completed. enabledSenders={}", enabledSenders);
    }
}
```

### После переноса

Старые файлы можно удалить:

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/service/EmailNotificationService.java
rfm-downloader/src/main/java/org/ikozmin/rfm/service/TelegramNotificationService.java
rfm-downloader/src/main/java/org/ikozmin/rfm/service/NotificationService.java
```

В `rfm-downloader/Main.java` вместо:

```java
import org.ikozmin.rfm.service.NotificationService;
```

использовать:

```java
import org.ikozmin.common.notification.NotificationDispatcher;
```

И в методе `sendNotificationIfNeeded` заменить:

```java
NotificationService notificationService = new NotificationService(config.getNotifications());
```

на:

```java
NotificationDispatcher notificationService = new NotificationDispatcher(config.getNotifications());
```

---

## 5.3. Добавить Notifications в ZenithConfig

### Файл

```text
zenith-processor/src/main/java/org/ikozmin/zenith/config/ZenithConfig.java
```

### Что добавить

Добавить импорт:

```java
import org.ikozmin.common.notification.NotificationsConfig;
```

В класс `ZenithConfig` добавить поле:

```java
@JsonProperty("Notifications")
private NotificationsConfig notifications;
```

Добавить getter:

```java
public NotificationsConfig getNotifications() {
    return notifications;
}
```

---

## 5.4. Создать текст уведомления Zenith

### Файл

```text
zenith-processor/src/main/java/org/ikozmin/zenith/notification/ZenithNotificationTextBuilder.java
```

### Код

```java
package org.ikozmin.zenith.notification;

import org.ikozmin.common.event.ZenithProcessingSummary;
import org.ikozmin.common.notification.NotificationMessage;

import java.nio.file.Path;

public final class ZenithNotificationTextBuilder {
    public NotificationMessage build(String catalog, ZenithProcessingSummary summary) {
        String subject = "Результат проверки Zenith: " + displayCatalogName(catalog);
        String lineSeparator = System.lineSeparator();
        StringBuilder body = new StringBuilder();

        body.append("Здравствуйте.").append(lineSeparator);
        body.append(lineSeparator);
        body.append("Завершена проверка в Zenith.").append(lineSeparator);
        body.append(lineSeparator);
        body.append("Перечень: ").append(displayCatalogName(catalog)).append(lineSeparator);

        if (summary.reportFile() != null) {
            body.append("Отчет: ").append(normalize(summary.reportFile())).append(lineSeparator);
        }

        body.append(lineSeparator);

        if (summary.newPersons() <= 0) {
            body.append("Новых лиц не найдено.").append(lineSeparator);
            body.append("Всего совпадений в отчете: ").append(summary.totalPersons()).append(lineSeparator);
            body.append(lineSeparator);
            body.append("Это автоматическое уведомление. Не надо на него отвечать.").append(lineSeparator);
            return new NotificationMessage(subject, body.toString());
        }

        body.append("Найдены новые лица: ").append(summary.newPersons()).append(lineSeparator);
        body.append(lineSeparator);

        for (int i = 0; i < summary.persons().size(); i++) {
            ZenithProcessingSummary.Person person = summary.persons().get(i);

            body.append(i + 1).append(". ").append(value(person.displayName())).append(lineSeparator);
            body.append("    Номер счета: ").append(value(person.accountNumber())).append(lineSeparator);
            body.append("    Организация: ").append(value(person.emitentName())).append(lineSeparator);

            if (person.packageDirectory() != null) {
                body.append("    Черновики ФЭС: ")
                        .append(normalize(person.packageDirectory()))
                        .append(lineSeparator);
            }

            body.append(lineSeparator);
        }

        body.append("Автоматическая отправка в Росфинмониторинг не выполнялась.").append(lineSeparator);
        body.append("Необходимо проверить подготовленные черновики и принять решение вручную.").append(lineSeparator);
        body.append(lineSeparator);
        body.append("Это автоматическое уведомление. Не надо на него отвечать.").append(lineSeparator);

        return new NotificationMessage(subject, body.toString());
    }

    private String displayCatalogName(String catalog) {
        if (catalog == null) {
            return "Неизвестный перечень";
        }

        return switch (catalog.toLowerCase()) {
            case "te2", "te21" -> "Террористы и экстремисты";
            case "mvk" -> "Решения МВК";
            case "un" -> "Перечень ООН";
            case "un-rus" -> "Перечень ООН на русском языке";
            default -> catalog;
        };
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }
}
```

---

## 5.5. Вызвать уведомления в ZenithProcessorMain

### Правило антидубля

В `zenith-processor` уведомление отправлять только если включен блок:

```json
"Notifications": {
  "Enabled": true
}
```

На центральном Zenith, который запускается из `rfm-downloader`, поставить:

```json
"Notifications": {
  "Enabled": false
}
```

В офисах поставить `true`.

### Файл

```text
zenith-processor/src/main/java/org/ikozmin/zenith/ZenithProcessorMain.java
```

### Добавить импорты

```java
import org.ikozmin.common.notification.NotificationDispatcher;
import org.ikozmin.common.notification.NotificationMessage;
import org.ikozmin.zenith.notification.ZenithNotificationTextBuilder;
```

### Добавить метод

Внутрь класса `ZenithProcessorMain` добавить:

```java
private void sendNotificationIfNeeded(ZenithConfig config, String catalog, ZenithProcessingSummary summary) {
    NotificationDispatcher dispatcher = new NotificationDispatcher(config.getNotifications());

    if (!dispatcher.isEnabled()) {
        return;
    }

    NotificationMessage message = new ZenithNotificationTextBuilder().build(catalog, summary);
    dispatcher.send(message);
}
```

### В processRegistryUpdatedEvent добавить отправку

Найти блок:

```java
ZenithProcessingSummary summary = workflowMode == ZenithWorkflowMode.IMPORT_ONLY
        ? workflowService.processImportOnly(claimedEvent.get().event())
        : workflowService.processFull(claimedEvent.get().event());

saveSummary(config, summary);
consumer.markProcessed(claimedEvent.get());
```

Заменить на:

```java
ZenithProcessingSummary summary = workflowMode == ZenithWorkflowMode.IMPORT_ONLY
        ? workflowService.processImportOnly(claimedEvent.get().event())
        : workflowService.processFull(claimedEvent.get().event());

saveSummary(config, summary);

if (workflowMode != ZenithWorkflowMode.IMPORT_ONLY) {
    sendNotificationIfNeeded(config, claimedEvent.get().event().catalog(), summary);
}

consumer.markProcessed(claimedEvent.get());
```

### В processImportCompletedEvent добавить отправку

Найти блок:

```java
ZenithProcessingSummary summary = workflowService.processCheckOnly(claimedEvent.get().event());
saveSummary(config, summary);
consumer.markProcessed(claimedEvent.get());
```

Заменить на:

```java
ZenithProcessingSummary summary = workflowService.processCheckOnly(claimedEvent.get().event());
saveSummary(config, summary);
sendNotificationIfNeeded(config, claimedEvent.get().event().catalog(), summary);
consumer.markProcessed(claimedEvent.get());
```

---

## 5.6. Добавить Notifications в zenith-config.template.json

### Файл

```text
zenith-processor/config/zenith-config.template.json
```

### Добавить блок верхнего уровня

Для центрального Zenith по умолчанию лучше оставить выключенным:

```json
"Notifications": {
  "Enabled": false,
  "Email": {
    "Enabled": false,
    "SmtpHost": "smtp.your-company.ru",
    "SmtpPort": 25,
    "SmtpUsername": "",
    "SmtpPassword": "",
    "UseTls": false,
    "From": "noreply@your-company.ru",
    "To": [
      "operator@your-company.ru"
    ],
    "Subject": "Результат проверки Zenith",
    "IncludeAttachment": false
  },
  "Telegram": {
    "Enabled": false,
    "Token": "YOUR_TELEGRAM_BOT_TOKEN",
    "ChatIds": [
      "YOUR_TELEGRAM_CHAT_ID"
    ],
    "ApiIp": "149.154.167.220"
  }
}
```

В офисном `zenith-config.json` включать так:

```json
"Notifications": {
  "Enabled": true,
  "Email": {
    "Enabled": true,
    "SmtpHost": "smtp.your-company.ru",
    "SmtpPort": 25,
    "SmtpUsername": "",
    "SmtpPassword": "",
    "UseTls": false,
    "From": "noreply@your-company.ru",
    "To": [
      "office-operator@your-company.ru"
    ],
    "Subject": "Результат проверки Zenith",
    "IncludeAttachment": false
  },
  "Telegram": {
    "Enabled": false,
    "Token": "",
    "ChatIds": [],
    "ApiIp": "149.154.167.220"
  }
}
```

---

# 6. Рабочие конфиги должны попадать в ZIP

## Что проверить

`distribution.xml` ожидает рабочие конфиги здесь:

```text
rfm-downloader/config/config.json
zenith-processor/config/zenith-config.json
```

Проверь, что рабочие файлы действительно лежат там.

В ZIP должны попасть:

```text
config/config.json
config/zenith-config.json
config/zenith/podft-report-filter-te21.xml
config/zenith/podft-report-filter-un.xml
config/zenith/podft-report-filter-mvk.xml
```

---

# 7. Логи

## Текущий статус

Проблема с переходом на новый день периодическая, а не постоянная.

Сначала проверить планировщик Windows:

```text
Не запускать новый экземпляр
```

Если после этого проблема повторится, тогда менять `logback.xml` на схему с датированным активным файлом без постоянного `rfm-client.log`.

Код `logback.xml` в этой инструкции больше не дублирую, чтобы не раздувать документ. Его стоит менять только если проблема повторится после запрета параллельных запусков.

---

# 8. Проверка после правок

## RFM

Запуск:

```bat
run-rfm.bat
```

Проверить:

1. Если обновились несколько реестров, приходит одно письмо/сообщение.
2. Файлы сохраняются в отдельные папки по каталогам.
3. Для каждого обновленного реестра создается событие `RegistryUpdated`.

## Zenith FULL

Запуск:

```bat
run-zenith-once.bat --mode FULL --require-event
```

Проверить:

1. Реестр импортирован в Zenith.
2. Событие `ZenithImportCompleted` создано в каждой папке из `ImportCompletedDirectories`.
3. Массовая проверка запущена.
4. Отчет выгружен.

## Zenith CHECK_ONLY в офисе

Запуск:

```bat
run-zenith-drain.bat --mode CHECK_ONLY
```

Проверить:

1. Клиентский Zenith берет события из своей `CheckDirectory`.
2. События переходят из `new` в `processed`.
3. При ошибке события уходят в `failed`.
4. Отчет выгружается в папку офиса.
