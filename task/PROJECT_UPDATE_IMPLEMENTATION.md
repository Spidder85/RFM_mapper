# Актуальная инструкция по оставшимся правкам

Дата актуализации: 2026-07-07.

Код проекта автоматически не менялся. Этот файл описывает, что именно нужно исправить вручную.

Корень проекта:

```text
G:\tmp\fedfsm\java
```

## Как читать инструкцию

Если написано `заменить полностью`, значит нужно заменить весь файл целиком.

Если написано `заменить метод`, значит меняется только указанный метод, остальной файл не трогать.

Если написано `создать файл`, значит ниже приведено полное содержимое нового файла.

Комментарии в `config*.json`, которые ты добавлял для меня, воспринимаются как замечания к обсуждению. В рабочих JSON-файлах комментариев быть не должно.

---

# 1. AppConfig: защита от null в Catalogs

## Зачем

В `rfm-downloader` уже добавлена обработка нескольких реестров через `Catalogs`.

Но сейчас метод:

```java
public List<String> getCatalogs() {
    return catalogs;
}
```

может вернуть `null`, если в рабочем `config.json` блок `Catalogs` отсутствует.

Тогда в `Main.resolveCatalogs()` будет ошибка при вызове:

```java
config.getCatalogs().isEmpty()
```

## Файл

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/config/AppConfig.java
```

## Что заменить

Найти метод:

```java
public List<String> getCatalogs() {
    return catalogs;
}
```

Заменить на:

```java
public List<String> getCatalogs() {
    return catalogs == null ? List.of() : catalogs;
}
```

## Почему не меняем весь AppConfig

Остальной класс уже рабочий. Здесь нужна только защита от `null`.

---

# 2. ZenithConfig: упростить конфиг и исправить Reports

## Зачем

В текущем `ZenithConfig` есть несколько связанных проблем:

1. `getReport(String catalog)` проверяет `report != null`, хотя должен проверять `reports != null`.
2. `Import.FileFormat` не должен быть пользовательской настройкой.
3. `Import.Append` не должен быть пользовательской настройкой.
4. `MassCheck.Periodic` не должен быть пользовательской настройкой.
5. `Storage.FoundPersonsFile` можно оставить в коде с дефолтом, но не выводить в пользовательский template.

Так как изменения связаны между собой, проще и надежнее заменить файл `ZenithConfig.java` полностью.

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
            if (reports != null && catalog != null && !catalog.isBlank()) {
                Report reportByCatalog = reports.get(catalog.trim().toLowerCase());

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

        @JsonProperty("ListCategories")
        private Map<String, String> listCategories;

        public boolean isEnabled() {
            return enabled == null || enabled;
        }

        public String getListCategory(String catalog) {
            if (listCategories == null || catalog == null || catalog.isBlank()) {
                return null;
            }

            return listCategories.get(catalog.trim().toLowerCase());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class MassCheck {
        @JsonProperty("Enabled")
        private Boolean enabled;

        public boolean isEnabled() {
            return enabled == null || enabled;
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
                    ? "config/zenith/podft-report-filter-te21.xml"
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

# 3. ZenithImportFormatResolver: вычислять file_format по catalog

## Зачем

По документации Zenith `file_format` зависит от типа реестра:

```text
te21 -> TerroristsXml
un   -> UnXml
mvk  -> CftXml
```

Это не настройка пользователя. Пользователь не должен руками выбирать `FileFormat`.

Для `UnXml` и `CftXml` нужен `list_category`. Это внешний код справочника Zenith. Его нужно взять из Zenith и указать в конфиге:

```json
"ListCategories": {
  "un": "...",
  "mvk": "..."
}
```

## Создать файл

```text
zenith-processor/src/main/java/org/ikozmin/zenith/service/ZenithImportFormatResolver.java
```

## Код

```java
package org.ikozmin.zenith.service;

import org.ikozmin.zenith.config.ZenithConfig;

import java.util.Locale;

public final class ZenithImportFormatResolver {
    public ImportFormat resolve(String catalog, ZenithConfig.Import importConfig) {
        String normalized = catalog == null
                ? ""
                : catalog.trim().toLowerCase(Locale.ROOT);

        return switch (normalized) {
            case "te2", "te21" -> new ImportFormat("TerroristsXml", null);
            case "un", "un-rus", "un_rus", "unrus" -> new ImportFormat(
                    "UnXml",
                    requireListCategory("un", importConfig)
            );
            case "mvk" -> new ImportFormat(
                    "CftXml",
                    requireListCategory("mvk", importConfig)
            );
            default -> throw new IllegalArgumentException("Unsupported Zenith import catalog: " + catalog);
        };
    }

    private String requireListCategory(String catalog, ZenithConfig.Import importConfig) {
        String value = importConfig == null ? null : importConfig.getListCategory(catalog);

        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Zenith list_category is required for catalog "
                            + catalog
                            + ". Configure Zenith.Import.ListCategories."
            );
        }

        return value.trim();
    }

    public record ImportFormat(
            String fileFormat,
            String listCategory
    ) {
    }
}
```

---

# 4. ZenithApiClient: добавить list_category в importPersonList

## Зачем

Текущий метод:

```java
public void importPersonList(Path file, String fileFormat, boolean append)
```

не умеет передавать `list_category`.

Для `un` и `mvk` это обязательный параметр.

## Файл

```text
zenith-processor/src/main/java/org/ikozmin/zenith/client/ZenithApiClient.java
```

## Что заменить

Найти старый метод:

```java
public void importPersonList(Path file, String fileFormat, boolean append) {
    try {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Person list file not found: " + file);
        }

        URI uri = uri("/zenith-object/api/v1/opercontrol/person_lists"
                + "?file_format=" + encode(fileFormat)
                + "&append=" + append);

        HttpRequest request = base(uri)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofFile(file))
                .build();

        sendNoBody(request, "import person list");
    } catch (Exception e) {
        throw new IllegalStateException("Failed to prepare person list import request. file=" + file, e);
    }
}
```

Заменить на новый полный метод:

```java
public void importPersonList(Path file, String fileFormat, String listCategory, boolean append) {
    try {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Person list file not found: " + file);
        }

        StringBuilder query = new StringBuilder()
                .append("?file_format=")
                .append(encode(fileFormat))
                .append("&append=")
                .append(append);

        if (listCategory != null && !listCategory.isBlank()) {
            query.append("&list_category=").append(encode(listCategory));
        }

        URI uri = uri("/zenith-object/api/v1/opercontrol/person_lists" + query);

        HttpRequest request = base(uri)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofFile(file))
                .build();

        sendNoBody(request, "import person list");
    } catch (Exception e) {
        throw new IllegalStateException("Failed to prepare person list import request. file=" + file, e);
    }
}
```

Остальной `ZenithApiClient` не менять.

---

# 5. ZenithWorkflowService: использовать resolver формата импорта

## Зачем

Сейчас `ZenithWorkflowService` берет формат импорта из конфига:

```java
String fileFormat = importConfig == null
        ? "TerroristsXml"
        : importConfig.getFileFormat();
```

Это неправильно для трех реестров. Формат должен вычисляться по `event.catalog()`.

Так как меняются поле, конструктор и два метода, надежнее заменить файл полностью.

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
    private final ZenithImportFormatResolver importFormatResolver;

    public ZenithWorkflowService(ZenithConfig config) {
        this.config = config;
        this.apiClient = new ZenithApiClient(config.getZenith());
        this.importFormatResolver = new ZenithImportFormatResolver();
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

        ZenithImportFormatResolver.ImportFormat importFormat = importFormatResolver.resolve(
                event.catalog(),
                importConfig
        );

        apiClient.importPersonList(
                event.registryFile(),
                importFormat.fileFormat(),
                importFormat.listCategory(),
                false
        );

        log.info("Registry list imported into Zenith. eventId={}, catalog={}, fileFormat={}, listCategory={}, file={}",
                event.eventId(),
                event.catalog(),
                importFormat.fileFormat(),
                importFormat.listCategory() == null ? "<not required>" : importFormat.listCategory(),
                event.registryFile());
    }

    private void runMassCheckIfEnabled(String catalog) {
        ZenithConfig.MassCheck massCheck = config.getZenith().getMassCheck();

        if (massCheck != null && !massCheck.isEnabled()) {
            log.info("Zenith mass check step is disabled");
            return;
        }

        apiClient.runMassCheck(false);

        log.info("Zenith AML/CFT mass check started. catalog={}, periodic=false", catalog);
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

# 6. zenith-config.template.json: упростить пользовательский конфиг

## Зачем

В template не нужно показывать пользователю внутренние настройки:

```text
Import.FileFormat
Import.Append
MassCheck.Periodic
Storage.FoundPersonsFile
Workflow.PollIntervalSeconds
```

`Workflow.PollIntervalSeconds` нужен только для `--watch`. Основной рекомендуемый запуск клиентской части - через `--drain` по планировщику, поэтому в template его можно не показывать.

## Файл

```text
zenith-processor/config/zenith-config.template.json
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
    "Mode": "FULL"
  },
  "Zenith": {
    "BaseUrl": "https://zenith-server/zenith-object",
    "UserName": "ZENITH_USER",
    "Password": "ZENITH_PASSWORD",
    "ServerName": "",
    "Import": {
      "Enabled": true,
      "ListCategories": {
        "un": "PUT_ZENITH_UN_LIST_CATEGORY_HERE",
        "mvk": "PUT_ZENITH_MVK_LIST_CATEGORY_HERE"
      }
    },
    "MassCheck": {
      "Enabled": true
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

## Что нужно заполнить руками

Значения:

```json
"un": "PUT_ZENITH_UN_LIST_CATEGORY_HERE",
"mvk": "PUT_ZENITH_MVK_LIST_CATEGORY_HERE"
```

нужно заменить на реальные `list_category` из Zenith.

Для `te21` `list_category` не нужен.

---

# 7. Рабочие конфиги должны попадать в ZIP

## Зачем

Сборка дистрибутива должна упаковывать рабочие конфиги, а не шаблоны.

Сейчас `distribution.xml` настроен на такие пути:

```text
rfm-downloader/config/config.json
zenith-processor/config/zenith-config.json
```

Но по текущей структуре рабочие файлы лежат здесь:

```text
rfm-downloader/config.json
zenith-processor/zenith-config.json
```

## Что сделать

Перенести рабочие файлы:

```text
rfm-downloader/config.json
```

в:

```text
rfm-downloader/config/config.json
```

и:

```text
zenith-processor/zenith-config.json
```

в:

```text
zenith-processor/config/zenith-config.json
```

## Почему так

`distribution/src/main/assembly/distribution.xml` уже ожидает именно такую структуру:

```xml
<fileSet>
    <directory>${project.parent.basedir}/rfm-downloader/config</directory>
    <outputDirectory>config</outputDirectory>
    <includes>
        <include>config.json</include>
    </includes>
</fileSet>

<fileSet>
    <directory>${project.parent.basedir}/zenith-processor/config</directory>
    <outputDirectory>config</outputDirectory>
    <includes>
        <include>zenith-config.json</include>
        <include>zenith/**</include>
    </includes>
</fileSet>
```

`distribution.xml` менять не нужно, если рабочие конфиги будут лежать в правильных папках.

---

# 8. Логи: периодически не происходит переход на новый день

## Что происходит

Проблема не постоянная. Обычно предыдущий день архивируется, но за неделю было несколько случаев, когда после полуночи приложение продолжило писать в старый файл.

Сначала нужно исключить пересечение запусков.

## Шаг 1. Проверить планировщик Windows

Для задачи запуска `rfm-downloader` включить правило:

```text
Do not start a new instance
```

В русском интерфейсе:

```text
Не запускать новый экземпляр
```

Это важно: если два экземпляра Java одновременно пишут в один и тот же активный лог, редкие проблемы ротации становятся вполне реальными.

## Шаг 2. Если проблема повторится, заменить logback

Если после запрета параллельного запуска проблема повторится, тогда заменить схему логирования на датированный активный файл без постоянного `rfm-client.log`.

### Файл

```text
rfm-downloader/src/main/resources/logback.xml
```

### Заменить полностью

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="LOG_DIR" value="logs"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <charset>UTF-8</charset>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_DIR}/rfm-client.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
            <maxFileSize>10MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>300MB</totalSizeCap>
        </rollingPolicy>

        <encoder>
            <charset>UTF-8</charset>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} %-5level %logger{48} - %msg%n</pattern>
        </encoder>
    </appender>

    <logger name="jdk.internal.httpclient.debug" level="OFF"/>
    <logger name="org.ikozmin.rfm" level="INFO"/>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

### Файл

```text
zenith-processor/src/main/resources/logback.xml
```

### Заменить полностью

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="LOG_DIR" value="logs"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <charset>UTF-8</charset>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_DIR}/zenith-processor.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
            <maxFileSize>10MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>300MB</totalSizeCap>
        </rollingPolicy>

        <encoder>
            <charset>UTF-8</charset>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} %-5level %logger{48} - %msg%n</pattern>
        </encoder>
    </appender>

    <logger name="org.ikozmin.zenith" level="INFO"/>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

## Что изменится после замены logback

Не будет постоянного файла:

```text
logs/rfm-client.log
logs/zenith-processor.log
```

Вместо этого будут файлы:

```text
logs/rfm-client.2026-07-07.0.log
logs/zenith-processor.2026-07-07.0.log
```

На следующий день создастся новый файл:

```text
logs/rfm-client.2026-07-08.0.log
logs/zenith-processor.2026-07-08.0.log
```

---

# 9. Проверка после правок

## Сборка

```bat
mvn -q clean package
```

## Проверить ZIP

В архиве должно быть:

```text
config/config.json
config/zenith-config.json
config/zenith/podft-report-filter-te21.xml
config/zenith/podft-report-filter-un.xml
config/zenith/podft-report-filter-mvk.xml
```

## Проверить импорт Zenith

Для `te21` запрос должен уходить с:

```text
file_format=TerroristsXml
```

Для `un`:

```text
file_format=UnXml
list_category=<значение из конфига>
```

Для `mvk`:

```text
file_format=CftXml
list_category=<значение из конфига>
```

## Проверить логи

Если менялся `logback.xml`, после запуска должен появиться файл вида:

```text
logs/rfm-client.YYYY-MM-DD.0.log
logs/zenith-processor.YYYY-MM-DD.0.log
```

