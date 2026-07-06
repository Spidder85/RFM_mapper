# План обновления проекта по task/TODO

Документ описывает, что менять в текущей рабочей версии проекта.

Исходный код автоматически не менялся. Все фрагменты ниже предназначены для ручного внесения.

Корень проекта:

```text
G:\tmp\fedfsm\java
```

## Как читать код в инструкции

1. Если раздел помечен как `Заменить полностью`, код приведен как полный итоговый файл.
2. Если раздел говорит `создать файл`, код приведен как полный новый файл.
3. Точечные вставки оставлены только для небольших изменений в уже существующих классах, где полная замена файла повышает риск случайно затереть рабочую логику.
4. Для крупных классов, которые меняются архитектурно, инструкция дает полный код. Например, `ZenithProcessorMain`, `ZenithConfig`, `ZenithWorkflowService`, `FoundPersonsStore`, `ZenithStateStore`.
5. Раздел про уведомления Zenith пока описывает архитектурное направление и минимальный builder текста. Полный перенос email/telegram-отправителей в `common` нужно делать отдельным шагом, чтобы не смешивать его с режимами обработки событий.

## Общая рекомендация по архитектуре

Сейчас не нужно переводить проект в полноценные микросервисы с отдельными службами, брокером сообщений и сетевым взаимодействием между модулями. Для этой задачи лучше оставить текущий Maven multi-module и развить его в событийную архитектуру на файловых очередях:

1. `rfm-downloader` мониторит реестры РФМ.
2. При обновлении каждого реестра сохраняет файл и публикует событие `RegistryUpdated`.
3. `zenith-processor` может работать в трех режимах:
   - `IMPORT_ONLY` - импортирует реестр в Zenith и публикует событие о завершении импорта;
   - `CHECK_ONLY` - по событию завершенного импорта запускает массовую проверку, выгружает отчет, анализирует результат;
   - `FULL` - делает импорт, проверку, отчет и анализ в одном запуске.
4. Если несколько клиентских ПК должны проверять независимо, импортирующий Zenith публикует событие завершенного импорта сразу в несколько папок. Каждый клиентский ПК читает свою папку.

Такой вариант проще сопровождать, он хорошо ложится на уже написанный код и не требует RabbitMQ/Kafka/PostgreSQL только ради передачи нескольких JSON-событий.

## Что уже есть и не требует повторной реализации

Эти пункты из TODO уже в проекте присутствуют:

1. Обработка `events/failed` частично есть: `run-zenith-retry-failed.bat` и `FileEventConsumer.requeueOldestFailed()`.
2. Очистка событий уже частично есть: `EventRetentionService`.
3. Лог Zenith уже есть: `zenith-processor/src/main/resources/logback.xml`.
4. Уведомления и подготовка ФЭС уже есть на уровне черновиков, автоматическую отправку ФЭС не добавляем.

Но часть реализации нужно расширить и привести к новой схеме.

---

# 1. rfm-downloader: мониторинг нескольких реестров

## Зачем

Сейчас приложение фактически обрабатывает один каталог за запуск: CLI `--catalog` или `DefaultCatalog` из конфига.

Нужно дать возможность штатно мониторить:

```text
te21
un
mvk
```

При этом CLI `--catalog` должен остаться для ручного запуска одного конкретного реестра.

## 1.1. AppConfig

Файл:

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/config/AppConfig.java
```

Добавить импорт:

```java
import java.util.List;
```

В класс `AppConfig` после поля:

```java
@JsonProperty("DefaultCatalog")
private String defaultCatalog;
```

добавить:

```java
@JsonProperty("Catalogs")
private List<String> catalogs;
```

После метода:

```java
public String getDefaultCatalog() {
    return defaultCatalog;
}
```

добавить:

```java
public List<String> getCatalogs() {
    return catalogs == null ? List.of() : catalogs;
}
```

## 1.2. config.template.json

Файл:

```text
rfm-downloader/config.template.json
```

После:

```json
"DefaultCatalog": "te21",
```

добавить:

```json
"Catalogs": [
  "te21",
  "un",
  "mvk"
],
```

Итоговая верхняя часть конфига должна быть такой:

```json
{
  "Credentials": {
    "UserName": "YOUR_RFM_USERNAME",
    "Password": "YOUR_RFM_PASSWORD"
  },
  "Certificate": {
    "SerialNumber": "YOUR_CERTIFICATE_SERIAL_NUMBER",
    "UseCryptoPro": true
  },
  "DefaultCatalog": "te21",
  "Catalogs": [
    "te21",
    "un",
    "mvk"
  ],
  "UseTestContour": false,
  "OutputDirectory": "downloads",
```

## 1.3. Main: заменить обработку одного каталога на цикл

Файл:

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/Main.java
```

### Добавить импорты

```java
import java.util.ArrayList;
import java.util.List;
```

### В методе `run()` заменить определение каталога

Найти:

```java
CatalogType catalogType = catalog != null
    ? CatalogType.from(catalog)
    : CatalogType.from(configLoader.defaultCatalog(config));
```

Заменить на:

```java
List<CatalogType> catalogTypes = resolveCatalogs(config, configLoader);
```

### В логах заменить строку каталога

Найти:

```java
log.info("Catalog: {}", catalogType.getCode());
```

Заменить на:

```java
log.info("Catalogs: {}", catalogTypes.stream().map(CatalogType::getCode).toList());
```

### Заменить блок обработки `RegistryUpdateService`

Найти блок от:

```java
UpdateResult result = retryPolicy.execute(
        "registry-update-" + catalogType.getCode(),
        () -> updateService.update(catalogType)
);
```

до:

```java
applyRetentionIfNeeded(config, workDir, downloadDir, catalogType);
```

Заменить на:

```java
List<UpdateResult> results = new ArrayList<>();

for (CatalogType catalogType : catalogTypes) {
    UpdateResult result = retryPolicy.execute(
            "registry-update-" + catalogType.getCode(),
            () -> updateService.update(catalogType)
    );

    results.add(result);

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
        sendNotificationIfNeeded(config, result, zenithSummary.orElse(null));

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

applyRetentionIfNeeded(config, workDir, downloadDir, catalogTypes);
```

### Заменить сигнатуру `applyRetentionIfNeeded`

Найти:

```java
private void applyRetentionIfNeeded(AppConfig config, Path workDir, Path downloadDir, CatalogType catalogType) {
```

Заменить на:

```java
private void applyRetentionIfNeeded(AppConfig config, Path workDir, Path downloadDir, List<CatalogType> catalogTypes) {
```

Внутри метода найти:

```java
retentionService.apply(workDir, downloadDir, catalogType);
```

Заменить на:

```java
for (CatalogType catalogType : catalogTypes) {
    retentionService.apply(workDir, downloadDir, catalogType);
}
```

### Добавить метод `resolveCatalogs`

В конец класса `Main`, перед методом `resolveContour`, добавить:

```java
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
```

## Почему именно так

1. Старый режим запуска не ломается.
2. `--catalog te21` по-прежнему запускает только один реестр.
3. Без `--catalog` приложение берет список из `Catalogs`.
4. Каждое обновление публикует отдельное событие с `catalog`, поэтому Zenith понимает, какой перечень обрабатывать.

---

# 2. common: сделать FileEventPublisher универсальным

## Зачем

Сейчас `FileEventPublisher` умеет публиковать только `RegistryUpdatedEvent`.

Для новой схемы Zenith должен публиковать другое событие - `ZenithImportCompletedEvent`.

## Файл

```text
common/src/main/java/org/ikozmin/common/event/FileEventPublisher.java
```

## Заменить полностью

```java
package org.ikozmin.common.event;

import org.ikozmin.common.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class FileEventPublisher {
    private final Path newDir;

    public FileEventPublisher(Path newDir) {
        this.newDir = newDir;
    }

    public Path publish(RegistryUpdatedEvent event) {
        return publish(event.eventId(), event);
    }

    public Path publish(ZenithImportCompletedEvent event) {
        return publish(event.eventId(), event);
    }

    public Path publish(String eventId, Object event) {
        try {
            Files.createDirectories(newDir);

            Path tempFile = newDir.resolve(eventId + ".json.tmp");
            Path finalFile = newDir.resolve(eventId + ".json");

            JsonMapper.get()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(tempFile.toFile(), event);

            Files.move(tempFile, finalFile, StandardCopyOption.ATOMIC_MOVE);

            return finalFile;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish event", e);
        }
    }
}
```

---

# 3. common: добавить событие завершенного импорта Zenith

## Зачем

Если Zenith работает в два этапа, после импорта реестра нужен отдельный event, который говорит клиентскому ПК:

```text
реестр такого-то типа импортирован, можно запускать массовую проверку и отчет
```

## Создать файл

```text
common/src/main/java/org/ikozmin/common/event/ZenithImportCompletedEvent.java
```

## Код

```java
package org.ikozmin.common.event;

import java.nio.file.Path;
import java.time.LocalDateTime;

public record ZenithImportCompletedEvent(
        String eventId,
        String eventType,
        LocalDateTime createdAt,
        String sourceEventId,
        String catalog,
        String idXml,
        Path registryFile,
        String importedAt
) {
    public static final String TYPE = "ZenithImportCompleted";
}
```

---

# 4. common: добавить consumer для событий импорта

## Зачем

`FileEventConsumer` читает только `RegistryUpdatedEvent`.

Для режима `CHECK_ONLY` нужен consumer, который читает `ZenithImportCompletedEvent`.

## Создать файл

```text
common/src/main/java/org/ikozmin/common/event/ZenithImportCompletedEventConsumer.java
```

## Код

```java
package org.ikozmin.common.event;

import org.ikozmin.common.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

public final class ZenithImportCompletedEventConsumer {
    private final Path newDir;
    private final Path processingDir;
    private final Path processedDir;
    private final Path failedDir;

    public ZenithImportCompletedEventConsumer(Path rootDir) {
        this.newDir = rootDir.resolve("new");
        this.processingDir = rootDir.resolve("processing");
        this.processedDir = rootDir.resolve("processed");
        this.failedDir = rootDir.resolve("failed");
    }

    public Optional<ClaimedEvent> claimNext() {
        try {
            Files.createDirectories(newDir);
            Files.createDirectories(processingDir);
            Files.createDirectories(processedDir);
            Files.createDirectories(failedDir);

            try (Stream<Path> files = Files.list(newDir)) {
                Optional<Path> next = files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted(Comparator.comparing(Path::getFileName))
                        .findFirst();

                if (next.isEmpty()) {
                    return Optional.empty();
                }

                Path source = next.get();
                Path target = processingDir.resolve(source.getFileName());

                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);

                ZenithImportCompletedEvent event = JsonMapper.get()
                        .readValue(target.toFile(), ZenithImportCompletedEvent.class);

                return Optional.of(new ClaimedEvent(event, target));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to claim Zenith import completed event", e);
        }
    }

    public void markProcessed(ClaimedEvent claimedEvent) {
        move(claimedEvent.file(), processedDir.resolve(claimedEvent.file().getFileName()));
    }

    public void markFailed(ClaimedEvent claimedEvent) {
        move(claimedEvent.file(), failedDir.resolve(claimedEvent.file().getFileName()));
    }

    private void move(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to move event file: " + source, e);
        }
    }

    public record ClaimedEvent(ZenithImportCompletedEvent event, Path file) {
    }
}
```

---

# 5. common: привести requeueOldestFailed к чистому виду

## Зачем

В текущем `FileEventConsumer.requeueOldestFailed()` есть лишние фигурные скобки в `catch`. Это не критичная ошибка, но код лучше привести в нормальный вид.

## Файл

```text
common/src/main/java/org/ikozmin/common/event/FileEventConsumer.java
```

## Заменить метод `requeueOldestFailed`

```java
public Optional<Path> requeueOldestFailed() {
    try {
        Files.createDirectories(newDir);
        Files.createDirectories(failedDir);

        try (Stream<Path> files = Files.list(failedDir)) {
            Optional<Path> next = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .findFirst();

            if (next.isEmpty()) {
                return Optional.empty();
            }

            Path source = next.get();
            Path target = newDir.resolve(source.getFileName());

            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);

            return Optional.of(target);
        }
    } catch (Exception e) {
        throw new IllegalStateException("Failed to requeue failed event", e);
    }
}
```

---

# 6. zenith-processor: режимы работы

## Зачем

По TODO нужны режимы:

1. Только импорт.
2. Только массовая проверка и отчет.
3. Полный цикл.

## Создать enum

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/config/ZenithWorkflowMode.java
```

Код:

```java
package org.ikozmin.zenith.config;

import java.util.Locale;

public enum ZenithWorkflowMode {
    IMPORT_ONLY,
    CHECK_ONLY,
    FULL;

    public static ZenithWorkflowMode from(String value) {
        if (value == null || value.isBlank()) {
            return FULL;
        }

        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "IMPORT_ONLY", "IMPORT" -> IMPORT_ONLY;
            case "CHECK_ONLY", "CHECK" -> CHECK_ONLY;
            case "FULL", "ALL" -> FULL;
            default -> throw new IllegalArgumentException("Unsupported Zenith workflow mode: " + value);
        };
    }
}
```

---

# 7. zenith-processor: расширить конфиг

## Зачем

Нужны:

1. Разные входные очереди для разных этапов.
2. Несколько выходных очередей после импорта.
3. Отдельные настройки отчетов по каждому перечню.
4. Режим работы Zenith.
5. Папка БД найденных лиц.

## Файл

```text
zenith-processor/src/main/java/org/ikozmin/zenith/config/ZenithConfig.java
```

## Заменить полностью

```java
package org.ikozmin.zenith.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class ZenithConfig {
    @JsonProperty("Events")
    private Events events;

    @JsonProperty("Workflow")
    private Workflow workflow;

    @JsonProperty("Zenith")
    private Zenith zenith;

    @JsonProperty("Results")
    private Results results;

    @JsonProperty("Storage")
    private Storage storage;

    public Events getEvents() {
        return events == null ? new Events() : events;
    }

    public Workflow getWorkflow() {
        return workflow == null ? new Workflow() : workflow;
    }

    public Zenith getZenith() {
        return zenith;
    }

    public Results getResults() {
        return results == null ? new Results() : results;
    }

    public Storage getStorage() {
        return storage == null ? new Storage() : storage;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Events {
        @JsonProperty("Directory")
        private String directory;

        @JsonProperty("RegistryUpdatedDirectory")
        private String registryUpdatedDirectory;

        @JsonProperty("ImportCompletedDirectories")
        private List<String> importCompletedDirectories;

        @JsonProperty("CheckDirectory")
        private String checkDirectory;

        public String getRegistryUpdatedDirectory() {
            if (registryUpdatedDirectory != null && !registryUpdatedDirectory.isBlank()) {
                return registryUpdatedDirectory;
            }

            return directory == null || directory.isBlank()
                    ? "events/registry-updated"
                    : directory;
        }

        public List<String> getImportCompletedDirectories() {
            if (importCompletedDirectories == null || importCompletedDirectories.isEmpty()) {
                return List.of("events/zenith-imported");
            }

            return importCompletedDirectories;
        }

        public String getCheckDirectory() {
            if (checkDirectory != null && !checkDirectory.isBlank()) {
                return checkDirectory;
            }

            return getImportCompletedDirectories().get(0);
        }

        public String getDirectory() {
            return getRegistryUpdatedDirectory();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Workflow {
        @JsonProperty("Mode")
        private String mode;

        @JsonProperty("PollIntervalSeconds")
        private Integer pollIntervalSeconds;

        public ZenithWorkflowMode getMode() {
            return ZenithWorkflowMode.from(mode);
        }

        public int getPollIntervalSeconds() {
            return pollIntervalSeconds == null ? 60 : pollIntervalSeconds;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Zenith {
        @JsonProperty("BaseUrl")
        private String baseUrl;

        @JsonProperty("UserName")
        private String userName;

        @JsonProperty("Password")
        private String password;

        @JsonProperty("ServerName")
        private String serverName;

        @JsonProperty("Import")
        private Import importConfig;

        @JsonProperty("MassCheck")
        private MassCheck massCheck;

        @JsonProperty("Report")
        private Report report;

        @JsonProperty("Reports")
        private Map<String, Report> reports;

        @JsonProperty("Fes")
        private Fes fes;

        public String getBaseUrl() {
            return baseUrl;
        }

        public String getUserName() {
            return userName;
        }

        public String getPassword() {
            String env = System.getenv("ZENITH_PASSWORD");
            return env == null || env.isBlank() ? password : env;
        }

        public String getServerName() {
            return serverName;
        }

        public Import getImportConfig() {
            return importConfig;
        }

        public MassCheck getMassCheck() {
            return massCheck;
        }

        public Report getReport() {
            return report;
        }

        public Report getReport(String catalog) {
            if (reports != null && catalog != null) {
                Report reportByCatalog = reports.get(catalog.toLowerCase());

                if (reportByCatalog != null) {
                    return reportByCatalog;
                }
            }

            return report;
        }

        public Fes getFes() {
            return fes == null ? new Fes() : fes;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Import {
        @JsonProperty("Enabled")
        private Boolean enabled;

        @JsonProperty("FileFormat")
        private String fileFormat;

        @JsonProperty("Append")
        private Boolean append;

        public boolean isEnabled() {
            return enabled == null || enabled;
        }

        public String getFileFormat() {
            return fileFormat == null || fileFormat.isBlank()
                    ? "TerroristsXml"
                    : fileFormat;
        }

        public boolean isAppend() {
            return append != null && append;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class MassCheck {
        @JsonProperty("Enabled")
        private Boolean enabled;

        @JsonProperty("Periodic")
        private Boolean periodic;

        public boolean isEnabled() {
            return enabled == null || enabled;
        }

        public boolean isPeriodic() {
            return periodic != null && periodic;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Report {
        @JsonProperty("Enabled")
        private Boolean enabled;

        @JsonProperty("OutDocType")
        private Integer outDocType;

        @JsonProperty("Filter")
        private Boolean filter;

        @JsonProperty("FilterTemplatePath")
        private String filterTemplatePath;

        @JsonProperty("OutputDirectory")
        private String outputDirectory;

        @JsonProperty("FileNamePrefix")
        private String fileNamePrefix;

        public boolean isEnabled() {
            return enabled == null || enabled;
        }

        public int getOutDocType() {
            return outDocType == null ? 10217 : outDocType;
        }

        public boolean isFilter() {
            return filter == null || filter;
        }

        public String getFilterTemplatePath() {
            return filterTemplatePath == null || filterTemplatePath.isBlank()
                    ? "config/zenith/podft-report-filter.xml"
                    : filterTemplatePath;
        }

        public String getOutputDirectory() {
            return outputDirectory == null || outputDirectory.isBlank()
                    ? "downloads/zenith-reports"
                    : outputDirectory;
        }

        public String getFileNamePrefix() {
            return fileNamePrefix == null || fileNamePrefix.isBlank()
                    ? "T38"
                    : fileNamePrefix;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Results {
        @JsonProperty("Directory")
        private String directory;

        public String getDirectory() {
            return directory == null || directory.isBlank()
                    ? "events/registry-updated/results"
                    : directory;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Storage {
        @JsonProperty("FoundPersonsFile")
        private String foundPersonsFile;

        public String getFoundPersonsFile() {
            return foundPersonsFile == null || foundPersonsFile.isBlank()
                    ? "data/zenith-found-persons.tsv"
                    : foundPersonsFile;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Fes {
        @JsonProperty("OutputDirectory")
        private String outputDirectory;

        public String getOutputDirectory() {
            return outputDirectory == null || outputDirectory.isBlank()
                    ? "downloads/fes-packages"
                    : outputDirectory;
        }
    }
}
```

---

# 8. zenith-config.template.json

## Файл

```text
zenith-processor/zenith-config.template.json
```

## Заменить полностью

```json
{
  "Events": {
    "RegistryUpdatedDirectory": "events/registry-updated",
    "ImportCompletedDirectories": [
      "events/zenith-imported/client-main"
    ],
    "CheckDirectory": "events/zenith-imported/client-main"
  },
  "Results": {
    "Directory": "events/registry-updated/results"
  },
  "Workflow": {
    "Mode": "FULL",
    "PollIntervalSeconds": 60
  },
  "Storage": {
    "FoundPersonsFile": "data/zenith-found-persons.tsv"
  },
  "Zenith": {
    "BaseUrl": "https://zenith-server/zenith-object",
    "UserName": "ZENITH_USER",
    "Password": "ZENITH_PASSWORD",
    "ServerName": "",
    "Import": {
      "Enabled": true,
      "FileFormat": "TerroristsXml",
      "Append": false
    },
    "MassCheck": {
      "Enabled": true,
      "Periodic": false
    },
    "Reports": {
      "te21": {
        "Enabled": true,
        "OutDocType": 10217,
        "Filter": true,
        "FilterTemplatePath": "config/zenith/podft-report-filter-te21.xml",
        "OutputDirectory": "downloads/zenith-reports/te21",
        "FileNamePrefix": "T38_terr"
      },
      "un": {
        "Enabled": true,
        "OutDocType": 10217,
        "Filter": true,
        "FilterTemplatePath": "config/zenith/podft-report-filter-un.xml",
        "OutputDirectory": "downloads/zenith-reports/un",
        "FileNamePrefix": "T38_un"
      },
      "mvk": {
        "Enabled": true,
        "OutDocType": 10217,
        "Filter": true,
        "FilterTemplatePath": "config/zenith/podft-report-filter-mvk.xml",
        "OutputDirectory": "downloads/zenith-reports/mvk",
        "FileNamePrefix": "T38_mvk"
      }
    },
    "Fes": {
      "OutputDirectory": "downloads/fes-packages"
    }
  }
}
```

## Важное замечание

Если реальные фильтры для `un` и `mvk` пока не готовы, сначала можно временно указать тот же файл фильтра, что и для `te21`. Но лучше хранить три отдельных файла, потому что в TODO прямо указано: отчеты, скорее всего, будут иметь разные фильтры.

---

# 9. zenith-processor: публикация события после импорта

## Создать файл

```text
zenith-processor/src/main/java/org/ikozmin/zenith/event/ZenithImportEventPublisher.java
```

## Код

```java
package org.ikozmin.zenith.event;

import org.ikozmin.common.event.FileEventPublisher;
import org.ikozmin.common.event.RegistryUpdatedEvent;
import org.ikozmin.common.event.ZenithImportCompletedEvent;
import org.ikozmin.zenith.config.ZenithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class ZenithImportEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(ZenithImportEventPublisher.class);

    private final ZenithConfig.Events eventsConfig;

    public ZenithImportEventPublisher(ZenithConfig.Events eventsConfig) {
        this.eventsConfig = eventsConfig;
    }

    public List<Path> publish(RegistryUpdatedEvent sourceEvent) {
        ZenithImportCompletedEvent event = new ZenithImportCompletedEvent(
                sourceEvent.eventId() + "-imported",
                ZenithImportCompletedEvent.TYPE,
                LocalDateTime.now(),
                sourceEvent.eventId(),
                sourceEvent.catalog(),
                sourceEvent.idXml(),
                sourceEvent.registryFile(),
                LocalDateTime.now().toString()
        );

        List<Path> publishedFiles = new ArrayList<>();

        for (String directory : eventsConfig.getImportCompletedDirectories()) {
            Path file = new FileEventPublisher(Path.of(directory).resolve("new")).publish(event);
            publishedFiles.add(file);

            log.info("Zenith import completed event published. catalog={}, file={}",
                    event.catalog(),
                    file.toAbsolutePath());
        }

        return publishedFiles;
    }
}
```

---

# 10. zenith-processor: обновить workflow service

## Зачем

Нужно разделить:

1. Импорт реестра.
2. Публикацию события после импорта.
3. Массовую проверку.
4. Выгрузку отчета.
5. Анализ отчета.

## Файл

```text
zenith-processor/src/main/java/org/ikozmin/zenith/service/ZenithWorkflowService.java
```

## Заменить полностью

```java
package org.ikozmin.zenith.service;

import org.ikozmin.common.event.RegistryUpdatedEvent;
import org.ikozmin.common.event.ZenithImportCompletedEvent;
import org.ikozmin.common.event.ZenithProcessingSummary;
import org.ikozmin.zenith.client.ZenithApiClient;
import org.ikozmin.zenith.config.ZenithConfig;
import org.ikozmin.zenith.event.ZenithImportEventPublisher;
import org.ikozmin.zenith.fes.FesPackage;
import org.ikozmin.zenith.fes.FesPackageService;
import org.ikozmin.zenith.person.FoundPersonsStore;
import org.ikozmin.zenith.report.ZenithReportAnalysis;
import org.ikozmin.zenith.report.ZenithReportAnalyzer;
import org.ikozmin.zenith.report.ZenithReportPerson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ZenithWorkflowService {
    private static final Logger log = LoggerFactory.getLogger(ZenithWorkflowService.class);

    private final ZenithConfig config;
    private final ZenithApiClient apiClient;

    public ZenithWorkflowService(ZenithConfig config) {
        this.config = config;
        this.apiClient = new ZenithApiClient(config.getZenith());
    }

    public ZenithProcessingSummary processFull(RegistryUpdatedEvent event) {
        log.info("Processing full Zenith workflow. eventId={}, catalog={}, file={}",
                event.eventId(),
                event.catalog(),
                event.registryFile());

        importPersonListIfEnabled(event);
        runMassCheckIfEnabled(event.catalog());

        ZenithProcessingSummary summary = createReportIfEnabled(
                event.eventId(),
                event.catalog(),
                event.idXml()
        );

        log.info("Full Zenith workflow completed. eventId={}", event.eventId());

        return summary;
    }

    public ZenithProcessingSummary processImportOnly(RegistryUpdatedEvent event) {
        log.info("Processing Zenith import only. eventId={}, catalog={}, file={}",
                event.eventId(),
                event.catalog(),
                event.registryFile());

        importPersonListIfEnabled(event);
        new ZenithImportEventPublisher(config.getEvents()).publish(event);

        return new ZenithProcessingSummary(
                event.eventId(),
                true,
                null,
                0,
                0,
                null,
                List.of(),
                "Реестр импортирован в Zenith. Каталог: " + event.catalog()
        );
    }

    public ZenithProcessingSummary processCheckOnly(ZenithImportCompletedEvent event) {
        log.info("Processing Zenith check only. eventId={}, sourceEventId={}, catalog={}",
                event.eventId(),
                event.sourceEventId(),
                event.catalog());

        runMassCheckIfEnabled(event.catalog());

        ZenithProcessingSummary summary = createReportIfEnabled(
                event.sourceEventId(),
                event.catalog(),
                event.idXml()
        );

        log.info("Zenith check workflow completed. eventId={}", event.eventId());

        return summary;
    }

    private void importPersonListIfEnabled(RegistryUpdatedEvent event) {
        ZenithConfig.Import importConfig = config.getZenith().getImportConfig();

        if (importConfig != null && !importConfig.isEnabled()) {
            log.info("Zenith import step is disabled");
            return;
        }

        String fileFormat = importConfig == null
                ? "TerroristsXml"
                : importConfig.getFileFormat();

        boolean append = importConfig != null && importConfig.isAppend();

        apiClient.importPersonList(
                event.registryFile(),
                fileFormat,
                append
        );

        log.info("Registry list imported into Zenith. eventId={}, catalog={}, file={}",
                event.eventId(),
                event.catalog(),
                event.registryFile());
    }

    private void runMassCheckIfEnabled(String catalog) {
        ZenithConfig.MassCheck massCheck = config.getZenith().getMassCheck();

        if (massCheck != null && !massCheck.isEnabled()) {
            log.info("Zenith mass check step is disabled");
            return;
        }

        boolean periodic = massCheck != null && massCheck.isPeriodic();

        apiClient.runMassCheck(periodic);

        log.info("Zenith AML/CFT mass check started. catalog={}, periodic={}",
                catalog,
                periodic);
    }

    private ZenithProcessingSummary createReportIfEnabled(String eventId, String catalog, String idXml) {
        ZenithConfig.Report report = config.getZenith().getReport(catalog);

        if (report == null || !report.isEnabled()) {
            log.info("Zenith report creation is disabled. catalog={}", catalog);
            return ZenithProcessingSummary.disabled(eventId);
        }

        ZenithReportService reportService = new ZenithReportService(apiClient, report);
        ZenithReportResult reportResult = reportService.createAndDownloadReport(eventId, catalog, idXml);

        ZenithReportAnalyzer analyzer = new ZenithReportAnalyzer();
        ZenithReportAnalysis analysis = analyzer.analyze(reportResult.reportFile());

        if (!analysis.hasPersons()) {
            log.info("No matches found in Zenith report. catalog={}, file={}",
                    catalog,
                    reportResult.reportFile().toAbsolutePath());

            return ZenithProcessingSummary.noNewPersons(
                    eventId,
                    reportResult.reportFile(),
                    0
            );
        }

        FoundPersonsStore personsStore = new FoundPersonsStore(
                Path.of(config.getStorage().getFoundPersonsFile())
        );

        List<ZenithReportPerson> newPersons = personsStore.findNewPersons(
                catalog,
                analysis.persons(),
                reportResult.endDate()
        );

        if (newPersons.isEmpty()) {
            log.info("Zenith report contains known persons only. catalog={}, totalPersons={}",
                    catalog,
                    analysis.persons().size());

            return ZenithProcessingSummary.noNewPersons(
                    eventId,
                    reportResult.reportFile(),
                    analysis.persons().size()
            );
        }

        Path fesOutputDir = Path.of(config.getZenith().getFes().getOutputDirectory());

        FesPackageService fesPackageService = new FesPackageService(fesOutputDir);
        List<FesPackage> packages = fesPackageService.preparePackages(newPersons, reportResult);

        personsStore.markFesPrepared(catalog, newPersons);

        List<ZenithProcessingSummary.Person> summaryPersons = new ArrayList<>();

        for (int i = 0; i < newPersons.size(); i++) {
            ZenithReportPerson person = newPersons.get(i);
            FesPackage pack = packages.get(i);

            summaryPersons.add(new ZenithProcessingSummary.Person(
                    person.displayName(),
                    person.accountNumber(),
                    person.emitentName(),
                    pack.directory()
            ));
        }

        log.warn("New list matches found. catalog={}, newPersons={}, packages={}",
                catalog,
                newPersons.size(),
                packages.stream().map(FesPackage::directory).toList());

        return new ZenithProcessingSummary(
                eventId,
                true,
                reportResult.reportFile(),
                analysis.persons().size(),
                newPersons.size(),
                fesOutputDir,
                summaryPersons,
                "Найдены новые лица. Перечень: " + catalog + ", количество: " + newPersons.size()
        );
    }
}
```

---

# 11. zenith-processor: обновить ZenithReportService

## Зачем

Сейчас метод принимает `RegistryUpdatedEvent`. В режиме `CHECK_ONLY` такого события уже нет, есть `ZenithImportCompletedEvent`.

Поэтому сервису отчета нужно передавать только необходимые значения: `eventId`, `catalog`, `idXml`.

## Файл

```text
zenith-processor/src/main/java/org/ikozmin/zenith/service/ZenithReportService.java
```

## Заменить метод `createAndDownloadReport`

```java
public ZenithReportResult createAndDownloadReport(String eventId, String catalog, String idXml) {
    try {
        LocalDate endDate = LocalDate.now();
        LocalDate beginDate = stateStore.loadLastSuccessfulCheckDate()
                .orElse(endDate);

        String filterXml = config.isFilter() ? loadFilterXml() : null;

        int outDocType = config.getOutDocType();

        ZenithApiClient.ReportCreateData data = new ZenithApiClient.ReportCreateData(
                outDocType,
                ASSIGN_OUT_DOC_NUM,
                ALL_EMITENTS,
                beginDate.toString(),
                endDate.toString()
        );

        ZenithApiClient.OutDocLink outDoc = apiClient.createReport(
                data,
                filterXml
        );

        Path outputDir = Path.of(config.getOutputDirectory());
        Files.createDirectories(outputDir);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yy_MM_dd");
        String fileName = config.getFileNamePrefix()
                + "_"
                + beginDate.format(formatter)
                + "-"
                + endDate.format(formatter)
                + "_"
                + catalog
                + ".xlsx";

        Path targetFile = outputDir.resolve(fileName);

        apiClient.downloadOutgoingDocument(outDoc.id(), REPORT_FORMAT, targetFile);

        stateStore.saveSuccessfulCheck(endDate, idXml, eventId);

        log.info("Zenith report downloaded. catalog={}, outDocId={}, file={}",
                catalog,
                outDoc.id(),
                targetFile.toAbsolutePath());

        return new ZenithReportResult(targetFile, beginDate, endDate, outDoc.id());
    } catch (Exception e) {
        throw new IllegalStateException("Failed to create and download Zenith report", e);
    }
}
```

## Удалить старый метод

Старый метод:

```java
public ZenithReportResult createAndDownloadReport(RegistryUpdatedEvent event)
```

нужно удалить полностью.

## Удалить лишний импорт

Удалить:

```java
import org.ikozmin.common.event.RegistryUpdatedEvent;
```

## Удалить метод

Удалить:

```java
private LocalDate resolveCurrentCheckDate(RegistryUpdatedEvent event) {
    if (event.downloadedAt() == null || event.downloadedAt().isBlank()) {
        return LocalDate.now();
    }

    return LocalDate.parse(event.downloadedAt().substring(0, 10));
}
```

## Почему так

Дата окончания отчета становится датой текущей проверки. Это соответствует инструкции: период отчета от даты предыдущей проверки до текущей даты.

---

# 12. zenith-processor: БД найденных лиц с учетом перечня

## Зачем

TODO требует хранить, в каком перечне найден человек.

Если этого не сделать, один и тот же клиент из разных перечней может быть ошибочно принят за уже известного.

Перед этим нужно исправить еще один связанный момент: состояние последней успешной проверки Zenith тоже должно храниться отдельно по каждому перечню.

---

# 12.0. zenith-processor: состояние проверки отдельно по перечням

## Зачем

Сейчас `ZenithReportService` использует один файл:

```text
downloads/zenith-state.properties
```

Если обрабатывать три перечня, один общий `lastSuccessfulCheckDate` начнет смешивать периоды отчетов. Например, сегодня успешно обработали `te21`, а затем `un` возьмет дату предыдущей проверки от `te21`. Это неправильно.

## Файл

```text
zenith-processor/src/main/java/org/ikozmin/zenith/state/ZenithStateStore.java
```

## Заменить полностью

```java
package org.ikozmin.zenith.state;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Properties;

public final class ZenithStateStore {
    private final Path file;

    public ZenithStateStore(Path file) {
        this.file = file;
    }

    public Optional<LocalDate> loadLastSuccessfulCheckDate(String catalog) {
        Properties properties = loadProperties();
        String value = properties.getProperty(key(catalog, "lastSuccessfulCheckDate"));

        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(LocalDate.parse(value));
    }

    public void saveSuccessfulCheck(String catalog, LocalDate checkDate, String idXml, String eventId) {
        try {
            Files.createDirectories(file.getParent());

            Properties properties = loadProperties();
            properties.setProperty(key(catalog, "lastSuccessfulCheckDate"), checkDate.toString());
            properties.setProperty(key(catalog, "lastSuccessfulIdXml"), idXml == null ? "" : idXml);
            properties.setProperty(key(catalog, "lastSuccessfulEventId"), eventId == null ? "" : eventId);

            try (var writer = Files.newBufferedWriter(file)) {
                properties.store(writer, "Zenith processing state");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save Zenith state: " + file.toAbsolutePath(), e);
        }
    }

    private Properties loadProperties() {
        Properties properties = new Properties();

        if (!Files.isRegularFile(file)) {
            return properties;
        }

        try (var reader = Files.newBufferedReader(file)) {
            properties.load(reader);
            return properties;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read Zenith state: " + file.toAbsolutePath(), e);
        }
    }

    private String key(String catalog, String name) {
        String normalizedCatalog = catalog == null || catalog.isBlank()
                ? "unknown"
                : catalog.trim().toLowerCase();

        return normalizedCatalog + "." + name;
    }
}
```

## Обновить ZenithReportService

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/service/ZenithReportService.java
```

В новом методе `createAndDownloadReport(String eventId, String catalog, String idXml)` заменить:

```java
LocalDate beginDate = stateStore.loadLastSuccessfulCheckDate()
        .orElse(endDate);
```

на:

```java
LocalDate beginDate = stateStore.loadLastSuccessfulCheckDate(catalog)
        .orElse(endDate);
```

И заменить:

```java
stateStore.saveSuccessfulCheck(endDate, idXml, eventId);
```

на:

```java
stateStore.saveSuccessfulCheck(catalog, endDate, idXml, eventId);
```

## 12.1. StoredPerson

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/person/StoredPerson.java
```

Заменить полностью:

```java
package org.ikozmin.zenith.person;

import java.time.LocalDate;

public record StoredPerson(
        String catalog,
        String personKey,
        String displayName,
        String normalizedName,
        String accountNumber,
        LocalDate firstFoundDate,
        LocalDate lastFoundDate,
        boolean fesPrepared,
        boolean fesSent
) {
}
```

## 12.2. FoundPersonsStore

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/person/FoundPersonsStore.java
```

Заменить полностью:

```java
package org.ikozmin.zenith.person;

import org.ikozmin.zenith.report.ZenithReportPerson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FoundPersonsStore {
    private static final String HEADER = "catalog\tpersonKey\tdisplayName\tnormalizedName\taccountNumber\tfirstFoundDate\tlastFoundDate\tfesPrepared\tfesSent";

    private final Path file;

    public FoundPersonsStore(Path file) {
        this.file = file;
    }

    public Map<String, StoredPerson> load() {
        try {
            if (!Files.isRegularFile(file)) {
                return new LinkedHashMap<>();
            }

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Map<String, StoredPerson> result = new LinkedHashMap<>();

            for (String line : lines) {
                if (line.isBlank() || line.equals(HEADER)) {
                    continue;
                }

                String[] parts = line.split("\t", -1);

                if (parts.length == 8) {
                    StoredPerson person = readLegacyPerson(parts);
                    result.put(storageKey(person.catalog(), person.personKey()), person);
                    continue;
                }

                if (parts.length < 9) {
                    continue;
                }

                StoredPerson person = new StoredPerson(
                        parts[0],
                        parts[1],
                        parts[2],
                        parts[3],
                        parts[4],
                        LocalDate.parse(parts[5]),
                        LocalDate.parse(parts[6]),
                        Boolean.parseBoolean(parts[7]),
                        Boolean.parseBoolean(parts[8])
                );

                result.put(storageKey(person.catalog(), person.personKey()), person);
            }

            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load found persons DB: " + file, e);
        }
    }

    public List<ZenithReportPerson> findNewPersons(String catalog, List<ZenithReportPerson> reportPersons, LocalDate foundDate) {
        Map<String, StoredPerson> stored = load();
        List<ZenithReportPerson> newPersons = new ArrayList<>();

        for (ZenithReportPerson person : reportPersons) {
            String storageKey = storageKey(catalog, person.personKey());
            StoredPerson existing = stored.get(storageKey);

            if (existing == null) {
                newPersons.add(person);
                stored.put(storageKey, new StoredPerson(
                        catalog,
                        person.personKey(),
                        person.displayName(),
                        person.normalizedName(),
                        person.accountNumber(),
                        foundDate,
                        foundDate,
                        false,
                        false
                ));
            } else {
                stored.put(storageKey, new StoredPerson(
                        existing.catalog(),
                        existing.personKey(),
                        existing.displayName(),
                        existing.normalizedName(),
                        existing.accountNumber(),
                        existing.firstFoundDate(),
                        foundDate,
                        existing.fesPrepared(),
                        existing.fesSent()
                ));
            }
        }

        save(stored);
        return newPersons;
    }

    public void markFesPrepared(String catalog, Collection<ZenithReportPerson> persons) {
        Map<String, StoredPerson> stored = load();

        for (ZenithReportPerson person : persons) {
            String storageKey = storageKey(catalog, person.personKey());
            StoredPerson existing = stored.get(storageKey);

            if (existing == null) {
                continue;
            }

            stored.put(storageKey, new StoredPerson(
                    existing.catalog(),
                    existing.personKey(),
                    existing.displayName(),
                    existing.normalizedName(),
                    existing.accountNumber(),
                    existing.firstFoundDate(),
                    existing.lastFoundDate(),
                    true,
                    existing.fesSent()
            ));
        }

        save(stored);
    }

    private StoredPerson readLegacyPerson(String[] parts) {
        return new StoredPerson(
                "te21",
                parts[0],
                parts[1],
                parts[2],
                parts[3],
                LocalDate.parse(parts[4]),
                LocalDate.parse(parts[5]),
                Boolean.parseBoolean(parts[6]),
                Boolean.parseBoolean(parts[7])
        );
    }

    private void save(Map<String, StoredPerson> persons) {
        try {
            Files.createDirectories(file.getParent());

            List<String> lines = new ArrayList<>();
            lines.add(HEADER);

            for (StoredPerson person : persons.values()) {
                lines.add(String.join("\t",
                        person.catalog(),
                        person.personKey(),
                        person.displayName(),
                        person.normalizedName(),
                        person.accountNumber(),
                        person.firstFoundDate().toString(),
                        person.lastFoundDate().toString(),
                        Boolean.toString(person.fesPrepared()),
                        Boolean.toString(person.fesSent())
                ));
            }

            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save found persons DB: " + file, e);
        }
    }

    private String storageKey(String catalog, String personKey) {
        return catalog + "|" + personKey;
    }
}
```

---

# 13. zenith-processor: обновить Main для режимов и автономного запуска

## Зачем

Нужны:

1. `--mode FULL`.
2. `--mode IMPORT_ONLY`.
3. `--mode CHECK_ONLY`.
4. `--watch` для автономного запуска.
5. Обработка разных event-очередей.

## Файл

```text
zenith-processor/src/main/java/org/ikozmin/zenith/ZenithProcessorMain.java
```

## Заменить полностью

```java
package org.ikozmin.zenith;

import org.ikozmin.common.event.FileEventConsumer;
import org.ikozmin.common.event.ProcessingSummaryStore;
import org.ikozmin.common.event.ZenithImportCompletedEventConsumer;
import org.ikozmin.common.event.ZenithProcessingSummary;
import org.ikozmin.zenith.config.ZenithConfig;
import org.ikozmin.zenith.config.ZenithConfigLoader;
import org.ikozmin.zenith.config.ZenithWorkflowMode;
import org.ikozmin.zenith.service.ZenithWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(
        name = "zenith-processor",
        mixinStandardHelpOptions = true,
        description = "Processes registry update events and imports them into Zenith"
)
public final class ZenithProcessorMain implements Callable<Integer> {
    private static final Logger log = LoggerFactory.getLogger(ZenithProcessorMain.class);

    @Option(names = {"-c", "--config"}, description = "Path to zenith config")
    private Path configPath = Path.of("config", "zenith-config.json");

    @Option(names = "--once", description = "Process one event and exit")
    private boolean once;

    @Option(names = "--drain", description = "Process all currently available events and exit")
    private boolean drain;

    @Option(names = "--watch", description = "Continuously watch event queue")
    private boolean watch;

    @Option(names = "--mode", description = "Workflow mode: FULL, IMPORT_ONLY, CHECK_ONLY")
    private String mode;

    @Option(names = "--require-event", description = "Fail if no event is available")
    private boolean requireEvent;

    @Option(names = "--retry-failed", description = "Move one failed event back to new queue before processing")
    private boolean retryFailed;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ZenithProcessorMain()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        try {
            ZenithConfig config = new ZenithConfigLoader().load(configPath);
            ZenithWorkflowMode workflowMode = resolveMode(config);

            if (watch) {
                runWatch(config, workflowMode);
                return 0;
            }

            if (drain) {
                return processDrain(config, workflowMode);
            }

            return processOnce(config, workflowMode);
        } catch (Exception e) {
            log.error("Zenith processor failed: {}", e.getMessage(), e);
            System.err.println("Zenith processor failed: " + e.getMessage());
            return 1;
        }
    }

    private void runWatch(ZenithConfig config, ZenithWorkflowMode workflowMode) throws InterruptedException {
        Duration delay = Duration.ofSeconds(config.getWorkflow().getPollIntervalSeconds());

        log.info("Zenith processor started in watch mode. mode={}, delay={}",
                workflowMode,
                delay);

        while (!Thread.currentThread().isInterrupted()) {
            int exitCode = processOnce(config, workflowMode);

            if (exitCode != 0 && exitCode != 3) {
                log.warn("Zenith watch iteration finished with non-zero code: {}", exitCode);
            }

            Thread.sleep(delay.toMillis());
        }
    }

    private Integer processDrain(ZenithConfig config, ZenithWorkflowMode workflowMode) {
        int processed = 0;

        while (true) {
            int exitCode = processOnce(config, workflowMode, false);

            if (exitCode == 3) {
                log.info("Zenith drain completed. processedEvents={}", processed);
                return 0;
            }

            if (exitCode != 0) {
                return exitCode;
            }

            processed++;
        }
    }

    private Integer processOnce(ZenithConfig config, ZenithWorkflowMode workflowMode) {
        return processOnce(config, workflowMode, requireEvent);
    }

    private Integer processOnce(ZenithConfig config, ZenithWorkflowMode workflowMode, boolean requireEventForIteration) {
        return switch (workflowMode) {
            case FULL -> processRegistryUpdatedEvent(config, ZenithWorkflowMode.FULL, requireEventForIteration);
            case IMPORT_ONLY -> processRegistryUpdatedEvent(config, ZenithWorkflowMode.IMPORT_ONLY, requireEventForIteration);
            case CHECK_ONLY -> processImportCompletedEvent(config, requireEventForIteration);
        };
    }

    private Integer processRegistryUpdatedEvent(
            ZenithConfig config,
            ZenithWorkflowMode workflowMode,
            boolean requireEventForIteration
    ) {
        FileEventConsumer consumer = new FileEventConsumer(
                Path.of(config.getEvents().getRegistryUpdatedDirectory())
        );

        if (retryFailed) {
            Optional<Path> requeued = consumer.requeueOldestFailed();

            if (requeued.isEmpty()) {
                log.info("No failed registry update events found");
                return 0;
            }

            log.info("Failed event requeued: {}", requeued.get().toAbsolutePath());
        }

        ZenithWorkflowService workflowService = new ZenithWorkflowService(config);
        Optional<FileEventConsumer.ClaimedEvent> claimedEvent = consumer.claimNext();

        if (claimedEvent.isEmpty()) {
            return noEvent(requireEventForIteration);
        }

        try {
            ZenithProcessingSummary summary = workflowMode == ZenithWorkflowMode.IMPORT_ONLY
                    ? workflowService.processImportOnly(claimedEvent.get().event())
                    : workflowService.processFull(claimedEvent.get().event());

            saveSummary(config, summary);
            consumer.markProcessed(claimedEvent.get());

            return 0;
        } catch (Exception e) {
            consumer.markFailed(claimedEvent.get());
            throw e;
        }
    }

    private Integer processImportCompletedEvent(ZenithConfig config, boolean requireEventForIteration) {
        ZenithImportCompletedEventConsumer consumer = new ZenithImportCompletedEventConsumer(
                Path.of(config.getEvents().getCheckDirectory())
        );

        ZenithWorkflowService workflowService = new ZenithWorkflowService(config);
        Optional<ZenithImportCompletedEventConsumer.ClaimedEvent> claimedEvent = consumer.claimNext();

        if (claimedEvent.isEmpty()) {
            return noEvent(requireEventForIteration);
        }

        try {
            ZenithProcessingSummary summary = workflowService.processCheckOnly(claimedEvent.get().event());
            saveSummary(config, summary);
            consumer.markProcessed(claimedEvent.get());

            return 0;
        } catch (Exception e) {
            consumer.markFailed(claimedEvent.get());
            throw e;
        }
    }

    private int noEvent(boolean requireEventForIteration) {
        if (requireEventForIteration) {
            log.error("No events found, but event is required");
            System.err.println("No events found, but event is required");
            return 3;
        }

        log.info("No events found");
        return 3;
    }

    private void saveSummary(ZenithConfig config, ZenithProcessingSummary summary) {
        ProcessingSummaryStore summaryStore = new ProcessingSummaryStore(
                Path.of(config.getResults().getDirectory())
        );

        Path summaryFile = summaryStore.save(summary);

        log.info("Zenith summary saved: {}", summaryFile.toAbsolutePath());
    }

    private ZenithWorkflowMode resolveMode(ZenithConfig config) {
        if (mode != null && !mode.isBlank()) {
            return ZenithWorkflowMode.from(mode);
        }

        return config.getWorkflow().getMode();
    }
}
```

---

# 14. bat-файлы для режимов Zenith

## 14.1. run-zenith-once.bat

Файл:

```text
run-zenith-once.bat
```

Оставить текущий файл, но запускать его теперь можно так:

```bat
run-zenith-once.bat --mode FULL
run-zenith-once.bat --mode IMPORT_ONLY
run-zenith-once.bat --mode CHECK_ONLY
```

Если в bat уже есть `%*`, менять его не нужно.

## 14.1.1. Добавить режим --drain

### Зачем

`--once` должен означать строго одно событие и выход.

Для клиентских ПК удобнее отдельный режим:

```text
--drain
```

Он обрабатывает все события, которые уже лежат в очереди, и завершает работу.

Это лучше, чем держать вечный Java-процесс на каждом клиентском ПК. Для эксплуатации под Windows надежнее запускать короткую задачу планировщиком каждые несколько минут:

```bat
run-zenith-drain.bat --mode CHECK_ONLY
```

Если событий нет, программа спокойно завершится с кодом `0`.

### Как Zenith должен обрабатывать несколько событий

Если в очереди одновременно лежат несколько файлов:

```text
events/zenith-imported/client-main/new/
  20260706-te21-....json
  20260706-un-....json
  20260706-mvk-....json
```

то:

1. `--once` обработает только первое событие.
2. `--drain` обработает все события по очереди и выйдет.
3. `--watch` будет постоянно просыпаться и обрабатывать события по одному.

Для клиентских ПК рекомендуемый режим:

```text
--drain
```

Для машины, которая импортирует реестры сразу после РФМ:

```text
--once --mode IMPORT_ONLY --require-event
```

Код режима `--drain` уже включен в полный код класса `ZenithProcessorMain` в разделе 13. Отдельно добавлять фрагменты в этот класс не нужно.

### Почему `noEvent(false)` тоже возвращает 3

Код `3` здесь означает не ошибку, а состояние:

```text
событий в очереди нет
```

Для обычного запуска с `--require-event` это ошибка бизнес-сценария.

Для `--drain` это нормальный признак завершения очереди, поэтому `processDrain()` превращает его в итоговый код `0`.

## 14.2. Создать run-zenith-watch.bat

Файл:

```text
run-zenith-watch.bat
```

Код:

```bat
@echo off
chcp 65001 > nul
setlocal
cd /d "%~dp0"

set "APP_HOME=%~dp0"
set "JAVA_HOME=%APP_HOME%jdk-21.0.10"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo Java not found: "%JAVA_HOME%\bin\java.exe"
  exit /b 2
)

"%JAVA_HOME%\bin\java.exe" ^
  -Dapp.home="%APP_HOME%" ^
  -cp "zenith-processor.jar;libs\*" ^
  org.ikozmin.zenith.ZenithProcessorMain ^
  --config config\zenith-config.json ^
  --watch ^
  %*

exit /b %ERRORLEVEL%
```

## 14.2.1. Создать run-zenith-drain.bat

### Зачем

Это основной рекомендуемый скрипт для клиентских ПК.

Он запускается планировщиком Windows каждые 5 минут, обрабатывает все накопленные события и завершает процесс.

Файл:

```text
run-zenith-drain.bat
```

Код:

```bat
@echo off
chcp 65001 > nul
setlocal
cd /d "%~dp0"

set "APP_HOME=%~dp0"
set "JAVA_HOME=%APP_HOME%jdk-21.0.10"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo Java not found: "%JAVA_HOME%\bin\java.exe"
  exit /b 2
)

"%JAVA_HOME%\bin\java.exe" ^
  -Dapp.home="%APP_HOME%" ^
  -cp "zenith-processor.jar;libs\*" ^
  org.ikozmin.zenith.ZenithProcessorMain ^
  --config config\zenith-config.json ^
  --drain ^
  %*

exit /b %ERRORLEVEL%
```

## 14.3. distribution.xml

Файл:

```text
distribution/src/main/assembly/distribution.xml
```

В блок bat-файлов добавить:

```xml
<include>run-zenith-watch.bat</include>
<include>run-zenith-drain.bat</include>
```

---

# 15. rfm-downloader: запуск Zenith после каждого нового реестра

## Рекомендация

Оставить текущую схему: РФМ после скачивания нового реестра запускает Zenith.

Но в `config.json` лучше явно указать режим:

```json
"ZenithTrigger": {
  "Enabled": true,
  "Command": "run-zenith-once.bat --require-event --mode FULL",
  "WorkingDirectory": ".",
  "TimeoutSeconds": 1800
}
```

Если будет двухэтапная схема с отдельными клиентскими ПК:

на машине импорта:

```json
"Command": "run-zenith-once.bat --require-event --mode IMPORT_ONLY"
```

на клиентских ПК запускать через планировщик или службу:

```bat
run-zenith-watch.bat --mode CHECK_ONLY
```

---

# 16. Отчеты по трем перечням

## Рекомендация

Нужно хранить отдельные фильтры:

```text
config/zenith/podft-report-filter-te21.xml
config/zenith/podft-report-filter-un.xml
config/zenith/podft-report-filter-mvk.xml
```

Даже если сначала они одинаковые, отдельные файлы позволят потом изменить один фильтр, не ломая остальные.

## Почему не делать один общий фильтр

В TODO уже указано, что отчеты, скорее всего, будут разными. Если оставить один общий фильтр, дальнейшая доработка снова полезет в код или конфиг.

---

# 17. Уведомления после проверки

## Рекомендация

Автоматическую отправку ФЭС пока не делать.

Правильная логика на текущем этапе:

1. Zenith выгружает отчет.
2. Анализатор находит новых лиц.
3. Система готовит черновики ФЭС.
4. Уведомление сотруднику пишет:
   - какой перечень;
   - сколько новых лиц;
   - где отчет;
   - где папки с черновиками ФЭС;
   - что решение об отправке принимает сотрудник.

Это соответствует инструкции: если выявлены лица, в течение одного рабочего дня отправить ФЭС, но решение остается за человеком.

## 17.1. Где должно формироваться итоговое уведомление

Если на клиентских ПК запускается только Zenith в режиме проверки:

```bat
run-zenith-drain.bat --mode CHECK_ONLY
```

то итоговое уведомление должен отправлять именно:

```text
zenith-processor
```

а не:

```text
rfm-downloader
```

Причина простая: `rfm-downloader` на клиентских ПК не запускается и не знает результат локальной массовой проверки.

Итоговая схема:

```text
Основной ПК:
  rfm-downloader
    -> скачал te21/un/mvk
    -> создал RegistryUpdated

  zenith-processor IMPORT_ONLY
    -> импортировал реестр в Zenith
    -> создал ZenithImportCompleted в папку клиента

Клиентский ПК:
  zenith-processor CHECK_ONLY
    -> увидел ZenithImportCompleted
    -> запустил массовую проверку
    -> выгрузил отчет
    -> проанализировал отчет
    -> подготовил черновики ФЭС
    -> отправил уведомление сотруднику
```

## 17.2. Что должно быть в уведомлении клиента

Уведомление должно быть написано для сотрудника, а не как технический лог.

Пример текста:

```text
Результат проверки Zenith

Перечень: te21
Период проверки: 2026-07-05 - 2026-07-06
Отчет: downloads/zenith-reports/te21/T38_terr_26_07_05-26_07_06_te21.xlsx

Найдены новые лица: 2

1. Иванов Иван Иванович
   Счет: 40702810...
   Организация: ООО "..."
   Черновик ФЭС: downloads/fes-packages/...

2. Петров Петр Петрович
   Счет: 40817810...
   Организация: Филиал ...
   Черновик ФЭС: downloads/fes-packages/...

Требуется проверка сотрудником.
При подтверждении совпадения ФЭС необходимо направить в Росфинмониторинг в установленный срок.
```

Если новых лиц нет:

```text
Результат проверки Zenith

Перечень: te21
Период проверки: 2026-07-05 - 2026-07-06
Отчет: downloads/zenith-reports/te21/T38_terr_26_07_05-26_07_06_te21.xlsx

Новых лиц не найдено.
```

## 17.3. Как реализовать уведомления в zenith-processor

### Рекомендация

Уведомления лучше сделать доступными для обоих модулей:

```text
rfm-downloader
zenith-processor
```

Для этого текущие классы отправки уведомлений нужно постепенно вынести в `common`.

Минимальный практичный вариант:

```text
common/src/main/java/org/ikozmin/common/notification/
  NotificationMessage.java
  NotificationSender.java
  EmailNotificationSender.java
  TelegramNotificationSender.java
  NotificationConfig.java
```

Но если не хочется сейчас переносить существующие классы, можно временно сделать отдельную реализацию в `zenith-processor`. С точки зрения чистой архитектуры лучше переносить в `common`, потому что отправка email/telegram не является ответственностью только `rfm-downloader`.

### Создать текстовый builder для Zenith

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/notification/ZenithNotificationTextBuilder.java
```

Код:

```java
package org.ikozmin.zenith.notification;

import org.ikozmin.common.event.ZenithProcessingSummary;

import java.nio.file.Path;

public final class ZenithNotificationTextBuilder {
    public String build(String catalog, ZenithProcessingSummary summary) {
        String lineSeparator = System.lineSeparator();
        StringBuilder body = new StringBuilder();

        body.append("Результат проверки Zenith").append(lineSeparator);
        body.append(lineSeparator);
        body.append("Перечень: ").append(catalog).append(lineSeparator);

        if (summary.reportFile() != null) {
            body.append("Отчет: ").append(normalize(summary.reportFile())).append(lineSeparator);
        }

        body.append(lineSeparator);

        if (summary.newPersons() <= 0) {
            body.append("Новых лиц не найдено.").append(lineSeparator);
            return body.toString();
        }

        body.append("Найдены новые лица: ").append(summary.newPersons()).append(lineSeparator);
        body.append(lineSeparator);

        for (int i = 0; i < summary.persons().size(); i++) {
            ZenithProcessingSummary.Person person = summary.persons().get(i);

            body.append(i + 1).append(". ").append(value(person.displayName())).append(lineSeparator);
            body.append("   Счет: ").append(value(person.accountNumber())).append(lineSeparator);
            body.append("   Организация: ").append(value(person.emitentName())).append(lineSeparator);

            if (person.packageDirectory() != null) {
                body.append("   Черновик ФЭС: ")
                        .append(normalize(person.packageDirectory()))
                        .append(lineSeparator);
            }

            body.append(lineSeparator);
        }

        body.append("Требуется проверка сотрудником.").append(lineSeparator);
        body.append("При подтверждении совпадения ФЭС необходимо направить в Росфинмониторинг в установленный срок.")
                .append(lineSeparator);

        return body.toString();
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }
}
```

### Где вызывать отправку

В `ZenithProcessorMain` уведомление нужно отправлять после:

```java
saveSummary(config, summary);
```

То есть в этих местах:

```java
ZenithProcessingSummary summary = workflowMode == ZenithWorkflowMode.IMPORT_ONLY
        ? workflowService.processImportOnly(claimedEvent.get().event())
        : workflowService.processFull(claimedEvent.get().event());

saveSummary(config, summary);
```

и:

```java
ZenithProcessingSummary summary = workflowService.processCheckOnly(claimedEvent.get().event());
saveSummary(config, summary);
```

В режиме `IMPORT_ONLY` уведомление о найденных лицах отправлять не нужно, потому что проверки еще не было.

В режимах `FULL` и `CHECK_ONLY` уведомление отправлять нужно.

Логика:

```java
if (workflowMode != ZenithWorkflowMode.IMPORT_ONLY) {
    sendZenithNotificationIfNeeded(config, catalog, summary);
}
```

Для `FULL` каталог берется из:

```java
claimedEvent.get().event().catalog()
```

Для `CHECK_ONLY` каталог берется из:

```java
claimedEvent.get().event().catalog()
```

## 17.4. Конфиг уведомлений для клиентского Zenith

В `zenith-config.template.json` нужно добавить отдельный блок:

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
    "Subject": "Результат проверки Zenith"
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

### Почему это должно быть в zenith-config.json

На разных клиентских ПК могут быть разные получатели:

```text
головной офис
филиал 1
филиал 2
```

Поэтому настройки уведомлений должны быть локальными для клиентского Zenith.

---

# 17.5. Запуск клиентского Zenith через планировщик Windows

## Рекомендованный способ

Для клиентских ПК лучше использовать не `--watch`, а короткий запуск по расписанию:

```bat
run-zenith-drain.bat --mode CHECK_ONLY
```

Периодичность:

```text
каждые 5 минут
```

Почему так лучше:

1. Если Java-процесс упал, следующий запуск снова стартует.
2. Не нужна Windows-служба.
3. Проще обновлять программу.
4. Проще сопровождать у пользователей.
5. Если событий накопилось несколько, `--drain` обработает все за один запуск.

## Пример структуры на клиентском ПК

```text
C:\rfm-automation-client\
  jdk-21.0.10\
  zenith-processor.jar
  libs\
  config\
    zenith-config.json
  run-zenith-drain.bat
  run-zenith-once.bat
  downloads\
  events\
  data\
  logs\
```

## Пример клиентского zenith-config.json

```json
{
  "Events": {
    "CheckDirectory": "\\\\server\\rfm-events\\client-01"
  },
  "Results": {
    "Directory": "events/registry-updated/results"
  },
  "Workflow": {
    "Mode": "CHECK_ONLY",
    "PollIntervalSeconds": 60
  },
  "Storage": {
    "FoundPersonsFile": "data/zenith-found-persons.tsv"
  },
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
        "operator@your-company.ru"
      ],
      "Subject": "Результат проверки Zenith"
    },
    "Telegram": {
      "Enabled": false,
      "Token": "",
      "ChatIds": [],
      "ApiIp": "149.154.167.220"
    }
  },
  "Zenith": {
    "BaseUrl": "https://zenith-server/zenith-object",
    "UserName": "ZENITH_USER",
    "Password": "ZENITH_PASSWORD",
    "ServerName": "",
    "Import": {
      "Enabled": false
    },
    "MassCheck": {
      "Enabled": true,
      "Periodic": false
    },
    "Reports": {
      "te21": {
        "Enabled": true,
        "OutDocType": 10217,
        "Filter": true,
        "FilterTemplatePath": "config/zenith/podft-report-filter-te21.xml",
        "OutputDirectory": "downloads/zenith-reports/te21",
        "FileNamePrefix": "T38_terr"
      },
      "un": {
        "Enabled": true,
        "OutDocType": 10217,
        "Filter": true,
        "FilterTemplatePath": "config/zenith/podft-report-filter-un.xml",
        "OutputDirectory": "downloads/zenith-reports/un",
        "FileNamePrefix": "T38_un"
      },
      "mvk": {
        "Enabled": true,
        "OutDocType": 10217,
        "Filter": true,
        "FilterTemplatePath": "config/zenith/podft-report-filter-mvk.xml",
        "OutputDirectory": "downloads/zenith-reports/mvk",
        "FileNamePrefix": "T38_mvk"
      }
    },
    "Fes": {
      "OutputDirectory": "downloads/fes-packages"
    }
  }
}
```

## Создание задачи планировщика через GUI

1. Открыть `Планировщик заданий`.
2. Создать задачу.
3. Вкладка `Общие`:
   - имя: `Zenith registry check`;
   - выполнять независимо от входа пользователя, если это разрешено политиками;
   - выполнять с наивысшими правами, если Zenith требует доступ к сетевым путям/локальным каталогам.
4. Вкладка `Триггеры`:
   - запускать ежедневно;
   - повторять каждые 5 минут;
   - длительность: бесконечно.
5. Вкладка `Действия`:
   - программа: `C:\rfm-automation-client\run-zenith-drain.bat`;
   - аргументы: `--mode CHECK_ONLY`;
   - рабочая папка: `C:\rfm-automation-client`.
6. Вкладка `Условия`:
   - отключить запуск только при питании от сети, если это рабочая станция;
   - при необходимости включить запуск при доступности сети.

## Создание задачи через schtasks

Пример:

```bat
schtasks /Create /TN "Zenith registry check" /SC MINUTE /MO 5 /TR "\"C:\rfm-automation-client\run-zenith-drain.bat\" --mode CHECK_ONLY" /ST 00:00 /F
```

Важно: если задача запускается не из папки приложения, bat-файл все равно перейдет в свою папку через:

```bat
cd /d "%~dp0"
```

поэтому относительные пути внутри приложения не сломаются.

---

# 18. Проверка после реализации

## Сборка

```bat
mvn -q clean package
```

## Проверить один реестр РФМ

```bat
run-rfm.bat --catalog te21
```

## Проверить все реестры из конфига

```bat
run-rfm.bat
```

## Проверить Zenith FULL

```bat
run-zenith-once.bat --mode FULL --require-event
```

## Проверить Zenith IMPORT_ONLY

```bat
run-zenith-once.bat --mode IMPORT_ONLY --require-event
```

После этого должно появиться событие:

```text
events/zenith-imported/client-main/new/*.json
```

## Проверить Zenith CHECK_ONLY

```bat
run-zenith-once.bat --mode CHECK_ONLY --require-event
```

## Проверить Zenith CHECK_ONLY с обработкой всей очереди

```bat
run-zenith-drain.bat --mode CHECK_ONLY
```

Если событий нет, запуск должен завершиться без ошибки.

Если в папке:

```text
events/zenith-imported/client-main/new
```

лежит несколько событий, команда должна обработать все события и переместить их в:

```text
events/zenith-imported/client-main/processed
```

## Проверить автономный режим

```bat
run-zenith-watch.bat --mode CHECK_ONLY
```

## Проверить клиентский режим через планировщик

1. Создать тестовое событие в клиентской папке:

```text
\\server\rfm-events\client-01\new
```

2. Запустить вручную:

```bat
run-zenith-drain.bat --mode CHECK_ONLY
```

3. Проверить:

```text
logs/zenith-processor.log
downloads/zenith-reports/<catalog>
downloads/fes-packages
data/zenith-found-persons.tsv
```

4. Только после ручной проверки добавлять запуск в планировщик Windows.

## Проверить БД найденных лиц

Файл:

```text
data/zenith-found-persons.tsv
```

В заголовке должна быть колонка:

```text
catalog
```

---

# 19. Что не делать сейчас

1. Не переводить проект в сетевые микросервисы.
2. Не добавлять брокер сообщений.
3. Не делать автоматическую отправку ФЭС.
4. Не делать отдельную серверную БД только ради очередей.
5. Не усложнять конфиг абсолютными путями.

Текущая задача хорошо решается через файловые события и portable distribution.
