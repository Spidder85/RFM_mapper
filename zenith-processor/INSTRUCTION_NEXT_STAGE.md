# Инструкция: расширение Zenith после скачивания отчета

## Стартовая точка

Эта инструкция начинается с текущего рабочего состояния проекта:

1. `rfm-downloader` скачивает новый реестр Росфинмониторинга.
2. После скачивания нового реестра запускается `zenith-processor`.
3. `zenith-processor` загружает реестр в Zenith.
4. Запускает массовую проверку.
5. Формирует и скачивает отчет Zenith в формате `Xlsx`.

Далее нужно расширить функционал:

1. Прочитать скачанный `Xlsx`-отчет.
2. Найти новые лица из списка террористов.
3. Исключить повторные строки одного и того же лица.
4. Сохранить найденных лиц в текстовую БД.
5. Для новых лиц подготовить черновики ФЭС-файлов.
6. Отправить уведомление по email/Telegram, что найдено новое лицо и где лежат черновики.

Отправку ФЭС через `formalized-message/send` на этом этапе не делаем.

## Почему используем Xlsx, а не отдельный XML

Для текущего этапа оставляем `Xlsx`.

Причины:

1. Цепочка выгрузки `Xlsx` уже проверена на реальном Zenith.
2. В `test4.xlsx` уже есть нужные поля:
   - `ЗЛ_Наименование`
   - `ЗЛ_НомерСчета`
   - `ЭМ_Наименование`
   - `ЗЛ_РискОснования`
3. Программа одинаково может читать и `Xlsx`, и XML, но `Xlsx` уже подтвержден тестом.
4. XML стоит добавлять только после отдельной проверки, что Zenith выгружает тот же отчет в стабильной XML-структуре.

Повторы ФИО в `Xlsx` не являются проблемой, если делать дедупликацию по ключу:

```text
нормализованное ФИО + номер счета
```

## Шаг 1. Добавить Apache POI в zenith-processor

Файл:

```text
zenith-processor/pom.xml
```

Внутрь блока `<dependencies>` добавить:

```xml
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.3.0</version>
</dependency>
```

Зачем:

Apache POI нужен для чтения отчета Zenith в формате `Xlsx`.

## Шаг 2. Убедиться, что зависимости Zenith попадут в установочный ZIP

После добавления POI зависимости должны попадать в `distribution`.

Файл:

```text
distribution/pom.xml
```

Внутрь `<plugins>` перед `maven-assembly-plugin` добавить:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-dependency-plugin</artifactId>
    <version>3.7.1</version>
    <executions>
        <execution>
            <id>copy-runtime-dependencies</id>
            <phase>package</phase>
            <goals>
                <goal>copy-dependencies</goal>
            </goals>
            <configuration>
                <outputDirectory>${project.build.directory}/libs</outputDirectory>
                <includeScope>runtime</includeScope>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Файл:

```text
distribution/src/main/assembly/distribution.xml
```

Найти блок:

```xml
<fileSet>
    <directory>${project.parent.basedir}/rfm-downloader/target/libs</directory>
    <outputDirectory>libs</outputDirectory>
    <includes>
        <include>*.jar</include>
    </includes>
</fileSet>
```

Заменить на:

```xml
<fileSet>
    <directory>${project.build.directory}/libs</directory>
    <outputDirectory>libs</outputDirectory>
    <includes>
        <include>*.jar</include>
    </includes>
</fileSet>
```

Зачем:

Раньше ZIP брал библиотеки только из `rfm-downloader`. После добавления анализа `Xlsx` новые библиотеки появятся в `zenith-processor`, поэтому их надо упаковывать через общий `distribution`-модуль.

## Шаг 3. Добавить результат скачивания отчета

Файл создать:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/service/ZenithReportResult.java
```

Код:

```java
package org.ikozmin.zenith.service;

import java.nio.file.Path;
import java.time.LocalDate;

public record ZenithReportResult(
        Path reportFile,
        LocalDate beginDate,
        LocalDate endDate,
        String outDocId
) {
}
```

Зачем:

После скачивания отчета workflow должен знать:

1. где лежит `Xlsx`;
2. за какой период сформирован отчет;
3. какой `outDocId` был создан в Zenith.

## Шаг 4. Изменить ZenithReportService

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/service/ZenithReportService.java
```

Найти сигнатуру метода:

```java
public Path createAndDownloadReport(RegistryUpdatedEvent event) {
```

Заменить на:

```java
public ZenithReportResult createAndDownloadReport(RegistryUpdatedEvent event) {
```

Найти в конце метода:

```java
return targetFile;
```

Заменить на:

```java
return new ZenithReportResult(targetFile, beginDate, endDate, outDoc.id());
```

Если в классе еще есть константа:

```java
private static final Path REPORT_FILTER_PATH = Path.of("config", "zenith", "podft-report-filter.xml");
```

ее удалить.

Метод `loadFilterXml()` заменить полностью:

```java
private String loadFilterXml() throws Exception {
    Path filterPath = Path.of(config.getFilterTemplatePath());

    if (!Files.isRegularFile(filterPath)) {
        throw new IllegalStateException("Zenith report filter file not found: "
                + filterPath.toAbsolutePath());
    }

    return Files.readString(filterPath);
}
```

Зачем:

1. Сервис теперь возвращает не просто путь к файлу, а полноценный результат.
2. Путь к XML-фильтру должен браться из `zenith-config.json`, потому что настройка уже есть в конфиге.

## Шаг 5. Создать модель строки отчета Zenith

Пакет создать:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/report
```

Файл создать:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/report/ZenithReportPerson.java
```

Код:

```java
package org.ikozmin.zenith.report;

public record ZenithReportPerson(
        String personKey,
        String displayName,
        String normalizedName,
        String accountNumber,
        String emitentName,
        String riskReason
) {
}
```

Зачем:

Это одна уникальная найденная запись из отчета Zenith.

`personKey` нужен для дедупликации:

```text
нормализованное ФИО + номер счета
```

## Шаг 6. Создать результат анализа отчета

Файл создать:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/report/ZenithReportAnalysis.java
```

Код:

```java
package org.ikozmin.zenith.report;

import java.nio.file.Path;
import java.util.List;

public record ZenithReportAnalysis(
        Path reportFile,
        List<ZenithReportPerson> persons
) {
    public boolean hasPersons() {
        return !persons.isEmpty();
    }
}
```

Зачем:

Это результат чтения `Xlsx`: файл отчета и список уникальных найденных лиц.

## Шаг 7. Создать анализатор Xlsx-отчета

Файл создать:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/report/ZenithReportAnalyzer.java
```

Код:

```java
package org.ikozmin.zenith.report;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ZenithReportAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(ZenithReportAnalyzer.class);

    private static final String CHECKS_SHEET_NAME = "Таблица_Проверок";
    private static final String NAME_COLUMN = "ЗЛ_Наименование";
    private static final String ACCOUNT_COLUMN = "ЗЛ_НомерСчета";
    private static final String EMITENT_COLUMN = "ЭМ_Наименование";
    private static final String RISK_COLUMN = "ЗЛ_РискОснования";

    public ZenithReportAnalysis analyze(Path reportFile) {
        if (reportFile == null || !Files.isRegularFile(reportFile)) {
            throw new IllegalArgumentException("Zenith report file not found: " + reportFile);
        }

        try (InputStream input = Files.newInputStream(reportFile);
             Workbook workbook = WorkbookFactory.create(input)) {

            Sheet sheet = workbook.getSheet(CHECKS_SHEET_NAME);
            if (sheet == null) {
                throw new IllegalStateException("Sheet not found in Zenith report: " + CHECKS_SHEET_NAME);
            }

            Map<String, Integer> columns = readHeader(sheet.getRow(0));

            int nameCol = requiredColumn(columns, NAME_COLUMN);
            int accountCol = requiredColumn(columns, ACCOUNT_COLUMN);
            int emitentCol = requiredColumn(columns, EMITENT_COLUMN);
            int riskCol = requiredColumn(columns, RISK_COLUMN);

            Map<String, ZenithReportPerson> persons = new LinkedHashMap<>();

            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }

                String displayName = cell(row, nameCol);
                String accountNumber = cell(row, accountCol);
                String riskReason = cell(row, riskCol);

                if (displayName.isBlank() || !isTerroristMatch(riskReason)) {
                    continue;
                }

                String normalizedName = normalizeName(displayName);
                String personKey = personKey(normalizedName, accountNumber);

                persons.putIfAbsent(personKey, new ZenithReportPerson(
                        personKey,
                        displayName,
                        normalizedName,
                        accountNumber,
                        cell(row, emitentCol),
                        riskReason
                ));
            }

            log.info("Zenith report analyzed. file={}, persons={}",
                    reportFile.toAbsolutePath(),
                    persons.size());

            return new ZenithReportAnalysis(reportFile, List.copyOf(persons.values()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to analyze Zenith report: " + reportFile, e);
        }
    }

    private Map<String, Integer> readHeader(Row row) {
        if (row == null) {
            throw new IllegalStateException("Zenith report header row is missing");
        }

        Map<String, Integer> result = new HashMap<>();

        for (Cell cell : row) {
            result.put(cell.getStringCellValue().trim(), cell.getColumnIndex());
        }

        return result;
    }

    private int requiredColumn(Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        if (index == null) {
            throw new IllegalStateException("Required column not found in Zenith report: " + name);
        }
        return index;
    }

    private String cell(Row row, int index) {
        Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) {
            return "";
        }

        DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("ru-RU"));
        return formatter.formatCellValue(cell).trim();
    }

    private boolean isTerroristMatch(String riskReason) {
        return riskReason != null
                && riskReason.toLowerCase(Locale.forLanguageTag("ru-RU")).contains("списке террористов");
    }

    private String normalizeName(String value) {
        return value == null
                ? ""
                : value.trim()
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.forLanguageTag("ru-RU"));
    }

    private String personKey(String normalizedName, String accountNumber) {
        String normalizedAccount = accountNumber == null
                ? ""
                : accountNumber.trim().replaceAll("\\s+", "");

        return normalizedName + "|" + normalizedAccount;
    }
}
```

Зачем:

1. Читает лист `Таблица_Проверок`.
2. Берет только строки с признаком террористического списка.
3. Убирает повторы по `personKey`.
4. Возвращает уникальных найденных лиц.

## Шаг 8. Создать текстовую БД найденных лиц

Пакет создать:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/person
```

Файл создать:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/person/StoredPerson.java
```

Код:

```java
package org.ikozmin.zenith.person;

import java.time.LocalDate;

public record StoredPerson(
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

Файл создать:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/person/FoundPersonsStore.java
```

Код:

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
    private static final String HEADER = "personKey\tdisplayName\tnormalizedName\taccountNumber\tfirstFoundDate\tlastFoundDate\tfesPrepared\tfesSent";

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

                String[] parts = line.split("\\t", -1);
                if (parts.length < 8) {
                    continue;
                }

                StoredPerson person = new StoredPerson(
                        parts[0],
                        parts[1],
                        parts[2],
                        parts[3],
                        LocalDate.parse(parts[4]),
                        LocalDate.parse(parts[5]),
                        Boolean.parseBoolean(parts[6]),
                        Boolean.parseBoolean(parts[7])
                );

                result.put(person.personKey(), person);
            }

            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load found persons DB: " + file, e);
        }
    }

    public List<ZenithReportPerson> findNewPersons(List<ZenithReportPerson> reportPersons, LocalDate foundDate) {
        Map<String, StoredPerson> stored = load();
        List<ZenithReportPerson> newPersons = new ArrayList<>();

        for (ZenithReportPerson person : reportPersons) {
            StoredPerson existing = stored.get(person.personKey());

            if (existing == null) {
                newPersons.add(person);
                stored.put(person.personKey(), new StoredPerson(
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
                stored.put(person.personKey(), new StoredPerson(
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

    public void markFesPrepared(Collection<ZenithReportPerson> persons) {
        Map<String, StoredPerson> stored = load();

        for (ZenithReportPerson person : persons) {
            StoredPerson existing = stored.get(person.personKey());
            if (existing == null) {
                continue;
            }

            stored.put(person.personKey(), new StoredPerson(
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

    private void save(Map<String, StoredPerson> persons) {
        try {
            Files.createDirectories(file.getParent());

            List<String> lines = new ArrayList<>();
            lines.add(HEADER);

            for (StoredPerson person : persons.values()) {
                lines.add(String.join("\t",
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
}
```

Зачем:

Файл `data/zenith-found-persons.tsv` будет простой текстовой БД:

1. кого уже находили;
2. когда нашли впервые;
3. когда нашли последний раз;
4. подготовлен ли черновик ФЭС;
5. отправлялся ли ФЭС в будущем.

## Шаг 9. Создать подготовку черновиков ФЭС

Пакет создать:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/fes
```

Файл создать:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/fes/FesPackage.java
```

Код:

```java
package org.ikozmin.zenith.fes;

import java.nio.file.Path;

public record FesPackage(
        String personName,
        Path directory,
        Path xmlFile,
        Path signFile
) {
}
```

Файл создать:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/fes/FesPackageService.java
```

Код:

```java
package org.ikozmin.zenith.fes;

import org.ikozmin.zenith.report.ZenithReportPerson;
import org.ikozmin.zenith.service.ZenithReportResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FesPackageService {
    private static final Path OUTPUT_DIR = Path.of("downloads", "fes-packages");
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    public List<FesPackage> preparePackages(List<ZenithReportPerson> persons, ZenithReportResult reportResult) {
        try {
            List<FesPackage> result = new ArrayList<>();

            for (ZenithReportPerson person : persons) {
                String safeName = safeFileName(person.personKey());
                Path dir = OUTPUT_DIR
                        .resolve(reportResult.endDate().format(DATE))
                        .resolve(safeName);

                Files.createDirectories(dir);

                Path xml = dir.resolve("FM03_DRAFT_" + safeName + ".xml");
                Path sign = dir.resolve("FM03_DRAFT_" + safeName + ".xml.sig");

                Files.writeString(xml, buildDraftXml(person, reportResult), StandardCharsets.UTF_8);

                if (!Files.exists(sign)) {
                    Files.writeString(sign,
                            "Черновик. Настоящая detached-подпись CryptoPro на этом этапе не формируется.",
                            StandardCharsets.UTF_8);
                }

                result.add(new FesPackage(person.displayName(), dir, xml, sign));
            }

            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to prepare FES packages", e);
        }
    }

    private String buildDraftXml(ZenithReportPerson person, ZenithReportResult reportResult) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!--
                  Черновик ФЭС FM03.
                  Отправка в Росфинмониторинг на этом этапе не выполняется.
                  Перед реальной отправкой XML нужно привести к утвержденному формату ФЭС
                  и подписать detached-подписью CryptoPro.
                -->
                <FM03_DRAFT>
                    <PersonKey>%s</PersonKey>
                    <PersonName>%s</PersonName>
                    <AccountNumber>%s</AccountNumber>
                    <EmitentName>%s</EmitentName>
                    <RiskReason>%s</RiskReason>
                    <CheckBeginDate>%s</CheckBeginDate>
                    <CheckEndDate>%s</CheckEndDate>
                    <ZenithOutDocId>%s</ZenithOutDocId>
                    <SourceReport>%s</SourceReport>
                </FM03_DRAFT>
                """.formatted(
                escapeXml(person.personKey()),
                escapeXml(person.displayName()),
                escapeXml(person.accountNumber()),
                escapeXml(person.emitentName()),
                escapeXml(person.riskReason()),
                reportResult.beginDate(),
                reportResult.endDate(),
                escapeXml(reportResult.outDocId()),
                escapeXml(reportResult.reportFile().toString())
        );
    }

    private String safeFileName(String value) {
        return value.toUpperCase(Locale.forLanguageTag("ru-RU"))
                .replaceAll("[^А-ЯA-Z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
```

Зачем:

Для каждого нового лица создается отдельный каталог с черновым XML и файлом-заглушкой подписи.

## Шаг 10. Подключить анализ отчета в ZenithWorkflowService

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/service/ZenithWorkflowService.java
```

Добавить импорты:

```java
import org.ikozmin.zenith.fes.FesPackage;
import org.ikozmin.zenith.fes.FesPackageService;
import org.ikozmin.zenith.person.FoundPersonsStore;
import org.ikozmin.zenith.report.ZenithReportAnalysis;
import org.ikozmin.zenith.report.ZenithReportAnalyzer;
import org.ikozmin.zenith.report.ZenithReportPerson;

import java.nio.file.Path;
import java.util.List;
```

Метод `createReportIfEnabled` заменить полностью:

```java
private void createReportIfEnabled(RegistryUpdatedEvent event) {
    ZenithConfig.Report report = config.getZenith().getReport();

    if (report == null || !report.isEnabled()) {
        log.info("Zenith report creation is disabled");
        return;
    }

    ZenithReportService reportService = new ZenithReportService(apiClient, report);
    ZenithReportResult reportResult = reportService.createAndDownloadReport(event);

    ZenithReportAnalyzer analyzer = new ZenithReportAnalyzer();
    ZenithReportAnalysis analysis = analyzer.analyze(reportResult.reportFile());

    if (!analysis.hasPersons()) {
        log.info("No terrorist matches found in Zenith report. file={}",
                reportResult.reportFile().toAbsolutePath());
        return;
    }

    FoundPersonsStore personsStore = new FoundPersonsStore(
            Path.of("data", "zenith-found-persons.tsv")
    );

    List<ZenithReportPerson> newPersons = personsStore.findNewPersons(
            analysis.persons(),
            reportResult.endDate()
    );

    if (newPersons.isEmpty()) {
        log.info("Zenith report contains known persons only. totalPersons={}",
                analysis.persons().size());
        return;
    }

    FesPackageService fesPackageService = new FesPackageService();
    List<FesPackage> packages = fesPackageService.preparePackages(newPersons, reportResult);

    personsStore.markFesPrepared(newPersons);

    log.warn("New terrorist list matches found. newPersons={}, packages={}",
            newPersons.size(),
            packages.stream().map(FesPackage::directory).toList());
}
```

Зачем:

Workflow после скачивания отчета запускает новый этап:

1. анализ `Xlsx`;
2. поиск новых лиц;
3. подготовка черновиков ФЭС.

## Шаг 11. Уведомления

Сейчас уведомления находятся в `rfm-downloader`.

На этом этапе есть два нормальных варианта.

Вариант 1, быстрый:

`zenith-processor` пока пишет в лог понятный текст о новых лицах и путях к черновикам. Уведомления подключить следующим отдельным шагом через общий модуль `common`.

Вариант 2, правильный:

Вынести интерфейс уведомлений в `common`, чтобы им могли пользоваться и `rfm-downloader`, и `zenith-processor`.

Для текущего этапа лучше сделать вариант 1, чтобы не смешивать анализ отчета с рефакторингом уведомлений.

Текст уведомления должен быть таким:

```text
В результате проверки перечня террористов в Zenith найдено новое лицо.

ФИО: Алиев Марат Султанович
Номер счета: 532
Организация: Акционерное общество "ВЗП "Булгар"
Период проверки: 2026-06-22 - 2026-06-24

Черновики файлов ФЭС подготовлены:
downloads/fes-packages/20260624/АЛИЕВ_МАРАТ_СУЛТАНОВИЧ_532

Автоматическая отправка в Росфинмониторинг не выполнялась.
```

Если найдено несколько новых лиц, перечислить каждого:

```text
В результате проверки перечня террористов в Zenith найдены новые лица: 2.

1. Алиев Марат Султанович
   Номер счета: 532
   Организация: Акционерное общество "ВЗП "Булгар"

2. ...

Черновики файлов ФЭС подготовлены в каталоге:
downloads/fes-packages/20260624

Автоматическая отправка в Росфинмониторинг не выполнялась.
```

## Шаг 12. Что должно появиться после запуска

После успешной работы должны появиться файлы:

```text
downloads/zenith-reports/*.xlsx
data/zenith-found-persons.tsv
downloads/fes-packages/<дата>/<personKey>/FM03_DRAFT_<personKey>.xml
downloads/fes-packages/<дата>/<personKey>/FM03_DRAFT_<personKey>.xml.sig
```

Пример:

```text
downloads/fes-packages/20260624/АЛИЕВ_МАРАТ_СУЛТАНОВИЧ_532/FM03_DRAFT_АЛИЕВ_МАРАТ_СУЛТАНОВИЧ_532.xml
```

## Шаг 13. Проверка сборки

Команда из корня Maven-проекта:

```bat
mvn -q clean package
```

Корень Maven-проекта:

```text
G:\tmp\fedfsm\java
```

После сборки проверить ZIP:

```text
target/distr/rfm-automation-2.1.0.zip
```

В ZIP должны быть:

```text
rfm-downloader.jar
zenith-processor.jar
libs/*.jar
config.json
zenith-config.json
config/zenith/podft-report-filter.xml
run-rfm.bat
run-zenith-once.bat
```

## Шаг 14. Проверка запуска

Для обычного полного сценария запускать:

```bat
run-rfm.bat
```

Если новый реестр скачан, `rfm-downloader` должен создать событие, после чего запустится Zenith-часть.

Для ручной проверки Zenith по уже созданному событию запускать:

```bat
run-zenith-once.bat
```

## Важные ограничения текущего этапа

На этом этапе не делаем:

1. Реальную отправку ФЭС в Росфинмониторинг.
2. Реальную detached-подпись CryptoPro.
3. Автоматическую загрузку квитанций ФЭС.
4. Отметку `fesSent=true`.

На этом этапе делаем:

1. Чтение отчета Zenith.
2. Поиск новых лиц.
3. Дедупликацию повторов.
4. Текстовую БД найденных лиц.
5. Черновики ФЭС.
6. Понятный текст для уведомления.
