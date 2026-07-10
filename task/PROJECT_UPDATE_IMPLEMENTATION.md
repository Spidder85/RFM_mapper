# Инструкция по доработкам от 2026-07-10

Корень проекта:

```text
G:\tmp\fedfsm\java
```

Исходный код автоматически не менялся. Ниже указано, что именно изменить вручную.

## Краткий вывод

По сегодняшним TODO нужно поправить не RFM, а в основном `zenith-processor`.

Что сейчас неправильно:

1. Если Zenith вернул ошибку по одному событию, `drain` прекращает обработку всей очереди.
2. Отсутствие листа `Таблица_Проверок` в XLSX считается ошибкой, хотя это может означать “совпадений нет”.
3. Ошибка “уже загружен более актуальный список” сейчас выглядит как падение программы, хотя это штатная бизнес-ситуация для устаревшего события.
4. В логах выводится имя logger/class; если нужно оставить только важность и текст, надо поменять pattern в `logback.xml`.
5. Events должны храниться 30 дней и затем удаляться одинаково для RFM и Zenith.

Правильная схема:

```text
ошибка одного события != ошибка всей программы
```

`zenith-processor --drain` должен обработать всю очередь:

```text
event 1 -> success
event 2 -> failed или skipped
event 3 -> success
drain завершился штатно
```

---

# 1. Ошибки Zenith API должны быть типизированы

## Зачем

Сейчас `ZenithApiClient` заворачивает почти все в обычный `IllegalStateException`. Из-за этого код выше не может понять:

```text
это сетевой сбой?
это ошибка формата?
это бизнес-ответ Zenith “список неактуален”?
```

Добавляем отдельное исключение `ZenithApiException`.

---

## 1.1. Создать ZenithApiException

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/client/ZenithApiException.java
```

Код:

```java
package org.ikozmin.zenith.client;

public final class ZenithApiException extends RuntimeException {
    private final String operation;
    private final int status;
    private final String body;

    public ZenithApiException(String operation, int status, String body) {
        super("Zenith API error. operation=" + operation + ", status=" + status + ", body=" + body);
        this.operation = operation;
        this.status = status;
        this.body = body;
    }

    public String operation() {
        return operation;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }

    public boolean isObsoletePersonListImport() {
        if (!"import person list".equals(operation)) {
            return false;
        }

        if (status != 400) {
            return false;
        }

        if (body == null) {
            return false;
        }

        String normalized = body.toLowerCase();

        return normalized.contains("уже загружен список")
                && normalized.contains("неактуален");
    }
}
```

---

## 1.2. Обновить ZenithApiClient

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/client/ZenithApiClient.java
```

В этом файле нужно заменить четыре метода: `importPersonList`, `sendString`, `sendNoBody`, `validate`.

### Заменить метод importPersonList

Найти текущий метод:

```java
public void importPersonList(Path file, String fileFormat, String listCategory, boolean append) {
```

Заменить весь метод на:

```java
public void importPersonList(Path file, String fileFormat, String listCategory, boolean append) {
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

    HttpRequest.BodyPublisher bodyPublisher;

    try {
        bodyPublisher = HttpRequest.BodyPublishers.ofFile(file);
    } catch (Exception e) {
        throw new IllegalStateException("Failed to prepare person list import request. file=" + file, e);
    }

    HttpRequest request = base(uri)
            .header("Content-Type", "application/octet-stream")
            .POST(bodyPublisher)
            .build();

    sendNoBody(request, "import person list");
}
```

Почему так:

1. Ошибка подготовки файла остается `IllegalStateException`.
2. Ошибка ответа Zenith больше не маскируется как “Failed to prepare”.
3. `ZenithApiException` поднимется наверх без лишней обертки.

### Заменить метод sendString

Найти:

```java
private String sendString(HttpRequest request, String operation) {
```

Заменить весь метод на:

```java
private String sendString(HttpRequest request, String operation) {
    try {
        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        validate(response.statusCode(), operation, response.body());
        return response.body();
    } catch (ZenithApiException e) {
        throw e;
    } catch (Exception e) {
        throw new IllegalStateException("Zenith API call failed: " + operation, e);
    }
}
```

### Заменить метод sendNoBody

Найти:

```java
private void sendNoBody(HttpRequest request, String operation) {
```

Заменить весь метод на:

```java
private void sendNoBody(HttpRequest request, String operation) {
    try {
        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        validate(response.statusCode(), operation, response.body());
    } catch (ZenithApiException e) {
        throw e;
    } catch (Exception e) {
        throw new IllegalStateException("Zenith API call failed: " + operation, e);
    }
}
```

### Заменить метод validate

Найти:

```java
private void validate(int status, String operation, String body) {
```

Заменить весь метод на:

```java
private void validate(int status, String operation, String body) {
    if (status >= 200 && status < 300) {
        return;
    }

    throw new ZenithApiException(operation, status, body);
}
```

---

# 2. Устаревший список Zenith должен быть skipped, а не падением программы

## Зачем

Пример из лога:

```text
Уже загружен список за дату 09.07.2026, загружаемый вами список от 07.07.2026 неактуален
```

Это не ошибка Java-программы. Это бизнес-ответ Zenith: событие устарело, импортировать его нельзя и не нужно.

Такое событие надо:

1. Не гонять бесконечно через `failed/retry`.
2. Пометить как обработанное.
3. Сохранить summary со статусом “пропущено”.
4. Продолжить остальные события.

---

## 2.1. Обновить ZenithProcessingSummary

Файл:

```text
common/src/main/java/org/ikozmin/common/event/ZenithProcessingSummary.java
```

Заменить файл полностью:

```java
package org.ikozmin.common.event;

import java.nio.file.Path;
import java.util.List;

public record ZenithProcessingSummary(
        String eventId,
        boolean processed,
        Path reportFile,
        int totalPersons,
        int newPersons,
        Path fesPackageRoot,
        List<Person> persons,
        String message
) {
    public static ZenithProcessingSummary disabled(String eventId) {
        return new ZenithProcessingSummary(
                eventId,
                true,
                null,
                0,
                0,
                null,
                List.of(),
                "Отчет Zenith отключен в конфигурации"
        );
    }

    public static ZenithProcessingSummary skipped(String eventId, String message) {
        return new ZenithProcessingSummary(
                eventId,
                true,
                null,
                0,
                0,
                null,
                List.of(),
                message
        );
    }

    public static ZenithProcessingSummary failed(String eventId, String message) {
        return new ZenithProcessingSummary(
                eventId,
                false,
                null,
                0,
                0,
                null,
                List.of(),
                message
        );
    }

    public static ZenithProcessingSummary noNewPersons(String eventId, Path reportFile, int totalPersons) {
        return new ZenithProcessingSummary(
                eventId,
                true,
                reportFile,
                totalPersons,
                0,
                null,
                List.of(),
                "Новых лиц не найдено"
        );
    }

    public record Person(
            String displayName,
            String accountNumber,
            String emitentName,
            Path packageDirectory
    ) {
    }
}
```

---

## 2.2. Обновить ZenithWorkflowService

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/service/ZenithWorkflowService.java
```

Добавить импорт:

```java
import org.ikozmin.zenith.client.ZenithApiException;
```

### Заменить метод processFull

Найти:

```java
public ZenithProcessingSummary processFull(RegistryUpdatedEvent event) {
```

Заменить весь метод на:

```java
public ZenithProcessingSummary processFull(RegistryUpdatedEvent event) {
    log.info("Processing full Zenith workflow. eventId={}, catalog={}, file={}",
            event.eventId(),
            event.catalog(),
            event.registryFile());

    boolean imported = importPersonListIfEnabled(event);

    if (!imported) {
        return ZenithProcessingSummary.skipped(
                event.eventId(),
                "Реестр не импортирован в Zenith: в Zenith уже загружен более актуальный список. Каталог: "
                        + event.catalog()
        );
    }

    new ZenithImportEventPublisher(config.getEvents()).publish(event);
    runMassCheckIfEnabled(event.catalog());

    ZenithProcessingSummary summary = createReportIfEnabled(
            event.eventId(),
            event.catalog(),
            event.idXml()
    );

    log.info("Full Zenith workflow completed. eventId={}", event.eventId());

    return summary;
}
```

### Заменить метод processImportOnly

Найти:

```java
public ZenithProcessingSummary processImportOnly(RegistryUpdatedEvent event) {
```

Заменить весь метод на:

```java
public ZenithProcessingSummary processImportOnly(RegistryUpdatedEvent event) {
    log.info("Processing Zenith import only. eventId={}, catalog={}, file={}",
            event.eventId(),
            event.catalog(),
            event.registryFile());

    boolean imported = importPersonListIfEnabled(event);

    if (!imported) {
        return ZenithProcessingSummary.skipped(
                event.eventId(),
                "Реестр не импортирован в Zenith: в Zenith уже загружен более актуальный список. Каталог: "
                        + event.catalog()
        );
    }

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
```

### Заменить метод importPersonListIfEnabled

Найти:

```java
private void importPersonListIfEnabled(RegistryUpdatedEvent event) {
```

Заменить весь метод на:

```java
private boolean importPersonListIfEnabled(RegistryUpdatedEvent event) {
    ZenithConfig.Import importConfig = config.getZenith().getImportConfig();

    if (importConfig != null && !importConfig.isEnabled()) {
        log.info("Zenith import step is disabled");
        return true;
    }

    ZenithImportFormatResolver.ImportFormat importFormat = importFormatResolver.resolve(
            event.catalog(),
            importConfig
    );

    try {
        apiClient.importPersonList(
                event.registryFile(),
                importFormat.fileFormat(),
                importFormat.listCategory(),
                false
        );
    } catch (ZenithApiException e) {
        if (e.isObsoletePersonListImport()) {
            log.warn("Registry list is obsolete for Zenith and will be skipped. eventId={}, catalog={}, file={}, apiMessage={}",
                    event.eventId(),
                    event.catalog(),
                    event.registryFile(),
                    e.body());
            return false;
        }

        throw e;
    }

    log.info("Registry list imported into Zenith. eventId={}, catalog={}, fileFormat={}, listCategory={}, file={}",
            event.eventId(),
            event.catalog(),
            importFormat.fileFormat(),
            importFormat.listCategory() == null ? "<not required>" : importFormat.listCategory(),
            event.registryFile());

    return true;
}
```

Почему `false` только для устаревшего списка:

1. Такой импорт нельзя исправить повтором.
2. Событие не должно оставаться в `failed`.
3. Остальные ошибки Zenith пока считаем ошибками события и отправляем в `failed`.

---

# 3. Drain должен продолжать очередь после ошибки одного события

## Зачем

Сейчас:

```text
event 1 -> ok
event 2 -> ошибка
drain остановился
event 3 -> не обработан
```

Нужно:

```text
event 1 -> ok
event 2 -> failed
event 3 -> ok
drain завершился
```

---

## 3.1. Обновить ZenithProcessorMain

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/ZenithProcessorMain.java
```

### Добавить константы

После строки:

```java
private static final Logger log = LoggerFactory.getLogger(ZenithProcessorMain.class);
```

добавить:

```java
private static final int EXIT_OK = 0;
private static final int EXIT_PROGRAM_ERROR = 1;
private static final int EXIT_EVENT_FAILED = 2;
private static final int EXIT_NO_EVENTS = 3;
```

### Заменить catch в call

Найти:

```java
return 1;
```

в методе `call()` и заменить на:

```java
return EXIT_PROGRAM_ERROR;
```

### Заменить processDrain

Найти:

```java
private Integer processDrain(ZenithConfig config, ZenithWorkflowMode workflowMode) {
```

Заменить весь метод на:

```java
private Integer processDrain(ZenithConfig config, ZenithWorkflowMode workflowMode) {
    int processed = 0;
    int failed = 0;

    while (true) {
        int exitCode = processOnce(config, workflowMode, false);

        if (exitCode == EXIT_NO_EVENTS) {
            log.info("Zenith drain completed. processedEvents={}, failedEvents={}", processed, failed);
            return EXIT_OK;
        }

        if (exitCode == EXIT_EVENT_FAILED) {
            failed++;
            continue;
        }

        if (exitCode != EXIT_OK) {
            return exitCode;
        }

        processed++;
    }
}
```

### Заменить processRegistryUpdatedEvent

Найти:

```java
private Integer processRegistryUpdatedEvent(
```

Заменить весь метод на:

```java
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
            return EXIT_OK;
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

        if (workflowMode != ZenithWorkflowMode.IMPORT_ONLY) {
            sendNotificationIfNeeded(config, claimedEvent.get().event().catalog(), summary);
        }

        consumer.markProcessed(claimedEvent.get());

        return EXIT_OK;
    } catch (Exception e) {
        log.error("Zenith registry event failed. eventId={}, catalog={}, error={}",
                claimedEvent.get().event().eventId(),
                claimedEvent.get().event().catalog(),
                e.getMessage(),
                e);

        saveFailureSummary(config, claimedEvent.get().event().eventId(), e);
        consumer.markFailed(claimedEvent.get());

        return EXIT_EVENT_FAILED;
    }
}
```

### Заменить processImportCompletedEvent

Найти:

```java
private Integer processImportCompletedEvent(ZenithConfig config, boolean requireEventForIteration) {
```

Заменить весь метод на:

```java
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
        sendNotificationIfNeeded(config, claimedEvent.get().event().catalog(), summary);
        consumer.markProcessed(claimedEvent.get());

        return EXIT_OK;
    } catch (Exception e) {
        log.error("Zenith check event failed. eventId={}, sourceEventId={}, catalog={}, error={}",
                claimedEvent.get().event().eventId(),
                claimedEvent.get().event().sourceEventId(),
                claimedEvent.get().event().catalog(),
                e.getMessage(),
                e);

        saveFailureSummary(config, claimedEvent.get().event().sourceEventId(), e);
        consumer.markFailed(claimedEvent.get());

        return EXIT_EVENT_FAILED;
    }
}
```

### Заменить noEvent

Найти:

```java
private int noEvent(boolean requireEventForIteration) {
```

Заменить весь метод на:

```java
private int noEvent(boolean requireEventForIteration) {
    if (requireEventForIteration) {
        log.error("No events found, but event is required");
        System.err.println("No events found, but event is required");
        return EXIT_NO_EVENTS;
    }

    log.info("No events found");
    return EXIT_NO_EVENTS;
}
```

### Добавить saveFailureSummary

После метода `saveSummary` добавить:

```java
private void saveFailureSummary(ZenithConfig config, String eventId, Exception e) {
    try {
        ZenithProcessingSummary summary = ZenithProcessingSummary.failed(
                eventId,
                "Ошибка обработки события Zenith: " + e.getMessage()
        );

        saveSummary(config, summary);
    } catch (Exception summaryError) {
        log.warn("Failed to save failure summary. eventId={}, error={}",
                eventId,
                summaryError.getMessage());
    }
}
```

Итог:

1. `once` вернет код `2`, если конкретное событие упало.
2. `drain` не остановится на коде `2`.
3. Событие уйдет в `failed`.
4. Summary сохранится, чтобы RFM увидел результат Zenith.

---

# 4. Отсутствие листа в XLSX не должно быть ошибкой

## Зачем

В TODO указан правильный сценарий:

```java
Sheet sheet = workbook.getSheet(CHECKS_SHEET_NAME);
if (sheet == null) {
    throw new IllegalStateException("Sheet not found in Zenith report: " + CHECKS_SHEET_NAME);
}
```

Если в отчете всего один лист и нет `Таблица_Проверок`, это может означать, что проверка никого не нашла. Для программы это не ошибка.

---

## 4.1. Обновить ZenithReportAnalyzer

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/report/ZenithReportAnalyzer.java
```

В методе `analyze` найти блок:

```java
Sheet sheet = workbook.getSheet(CHECKS_SHEET_NAME);
if (sheet == null) {
    throw new IllegalStateException("Sheet not found in Zenith report: " + CHECKS_SHEET_NAME);
}
```

Заменить на:

```java
Sheet sheet = workbook.getSheet(CHECKS_SHEET_NAME);
if (sheet == null) {
    log.info("Zenith report does not contain checks sheet. Treating report as empty. file={}, sheet={}",
            reportFile.toAbsolutePath(),
            CHECKS_SHEET_NAME);

    return new ZenithReportAnalysis(reportFile, List.of());
}
```

Важно: импорт `java.util.List` уже есть, добавлять его не нужно.

---

# 5. Уведомление должно уметь показать failed/skipped summary

## Зачем

После добавления `ZenithProcessingSummary.failed(...)` и `skipped(...)` уведомления не должны писать “Новых лиц не найдено”, если реально событие не обработано или пропущено.

---

## 5.1. Обновить общий ZenithNotificationTextBuilder

Файл:

```text
common/src/main/java/org/ikozmin/common/notification/ZenithNotificationTextBuilder.java
```

В методе `appendResultBlock` после проверки:

```java
if (summary == null) {
```

и до блока:

```java
if (summary.reportFile() != null) {
```

добавить:

```java
if (!summary.processed()) {
    body.append(indent)
            .append("Проверка в Zenith завершилась ошибкой.")
            .append(lineSeparator);
    body.append(indent)
            .append("Причина: ")
            .append(value(summary.message()))
            .append(lineSeparator);
    return;
}

if (summary.reportFile() == null && summary.totalPersons() == 0 && summary.newPersons() == 0
        && summary.message() != null && !summary.message().isBlank()) {
    body.append(indent)
            .append(summary.message())
            .append(lineSeparator);
    return;
}
```

Смысл:

1. `processed=false` отображается как ошибка обработки.
2. `skipped(...)` отображается как пропущенное событие с понятным текстом.
3. Нормальный пустой отчет продолжает отображаться как “Новых лиц не найдено”.

---

# 6. Логи без имени logger/class

## Ответ на вопрос из TODO

Сейчас в логе выводится не имя метода, а имя logger/class:

```xml
%logger{48}
```

Если нужно оставить только дату, важность и текст, меняем pattern.

---

## 6.1. Обновить zenith logback.xml

Файл:

```text
zenith-processor/src/main/resources/logback.xml
```

Заменить файл полностью:

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<configuration>
    <property name="LOG_DIR" value="logs"/>
    <property name="LOG_FILE" value="${LOG_DIR}/zenith-processor.log"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <charset>UTF-8</charset>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} %-5level - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_FILE}</file>

        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_DIR}/zenith-processor.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>10MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>300MB</totalSizeCap>
        </rollingPolicy>

        <encoder>
            <charset>UTF-8</charset>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} %-5level - %msg%n</pattern>
        </encoder>
    </appender>

    <logger name="org.ikozmin.zenith" level="INFO"/>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

Что изменилось:

1. Убран `%logger{48}`.
2. Старые логи будут архивироваться как `.log.gz`.
3. Активный файл останется `logs/zenith-processor.log`.

---

# 7. Events: хранить месяц и удалять

## Решение

Делаем единое правило для всех event-очередей:

```text
events хранятся 30 дней, затем удаляются
```

Это касается:

```text
rfm-downloader: events/registry-updated
zenith-processor: events/registry-updated
zenith-processor: events/zenith-imported/...
```

Не делаем вечный `archive`, потому что он все равно будет бесконечно расти. Для этой программы достаточно месячного технологического следа: за месяц можно посмотреть историю обработки, failed-события и summary, после чего файлы безопасно удаляются.

Чтобы не дублировать код, сервис очистки кладем в `common`.

---

## 7.1. Создать общий EventRetentionService

Создать файл:

```text
common/src/main/java/org/ikozmin/common/event/EventRetentionService.java
```

Код:

```java
package org.ikozmin.common.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;

public final class EventRetentionService {
    private static final Logger log = LoggerFactory.getLogger(EventRetentionService.class);

    public static final int DEFAULT_KEEP_DAYS = 30;

    private static final List<String> EVENT_SUBDIRECTORIES = List.of(
            "processed",
            "failed",
            "results"
    );

    private final int keepDays;

    public EventRetentionService() {
        this(DEFAULT_KEEP_DAYS);
    }

    public EventRetentionService(int keepDays) {
        this.keepDays = Math.max(1, keepDays);
    }

    public void apply(Path eventRootDir) {
        if (eventRootDir == null) {
            return;
        }

        for (String subdirectory : EVENT_SUBDIRECTORIES) {
            clean(eventRootDir.resolve(subdirectory));
        }
    }

    private void clean(Path directory) {
        if (!Files.isDirectory(directory)) {
            return;
        }

        Instant threshold = Instant.now().minus(keepDays, ChronoUnit.DAYS);

        try (Stream<Path> files = Files.list(directory)) {
            files
                    .filter(Files::isRegularFile)
                    .filter(file -> isOlderThan(file, threshold))
                    .forEach(this::deleteQuietly);
        } catch (Exception e) {
            log.warn("Event retention failed. dir={}, keepDays={}, error={}",
                    directory,
                    keepDays,
                    e.getMessage());
        }
    }

    private boolean isOlderThan(Path file, Instant threshold) {
        try {
            return Files.getLastModifiedTime(file).toInstant().isBefore(threshold);
        } catch (Exception e) {
            return false;
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
            log.info("Old event file deleted. file={}", file.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to delete old event file. file={}, error={}",
                    file,
                    e.getMessage());
        }
    }
}
```

Почему `common`:

1. Очереди RFM и Zenith имеют одинаковую структуру.
2. Не нужно держать два почти одинаковых класса.
3. Не нужно добавлять новый блок в пользовательский `zenith-config.json`.

---

## 7.2. Удалить старый RFM EventRetentionService

Удалить файл:

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/service/EventRetentionService.java
```

Он больше не нужен, потому что очистка переезжает в `common`.

---

## 7.3. Обновить rfm-downloader Main

Файл:

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/Main.java
```

Заменить импорт:

```java
import org.ikozmin.rfm.service.EventRetentionService;
```

на:

```java
import org.ikozmin.common.event.EventRetentionService;
```

Найти в методе `applyRetentionIfNeeded`:

```java
new EventRetentionService(config.getRetention()).apply(eventRootDir);
```

Заменить на:

```java
new EventRetentionService().apply(eventRootDir);
```

Итог: `events/registry-updated/processed`, `failed`, `results` будут храниться 30 дней, затем удаляться.

---

## 7.4. Обновить rfm-downloader config.template.json

Файл:

```text
rfm-downloader/config/config.template.json
```

В блоке:

```json
"Retention": {
  "Enabled": true,
  "KeepAuditDays": 60,
  "KeepDownloadedVersions": 10,
  "KeepProcessedEventDays": 30,
  "KeepFailedEventDays": 180,
  "KeepResultEventDays": 30
}
```

заменить на:

```json
"Retention": {
  "Enabled": true,
  "KeepAuditDays": 60,
  "KeepDownloadedVersions": 10
}
```

Почему убрать event-поля:

1. Для events принято единое правило 30 дней.
2. Пользовательский конфиг не должен разрастаться служебными настройками.
3. Zenith будет использовать тот же срок без отдельного конфига.

Поля `KeepProcessedEventDays`, `KeepFailedEventDays`, `KeepResultEventDays` можно оставить в `RetentionConfig` для обратной совместимости, но больше не использовать в новой логике.

---

## 7.5. Добавить очистку Zenith events

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/ZenithProcessorMain.java
```

Добавить импорт:

```java
import org.ikozmin.common.event.EventRetentionService;
```

### Для once/drain

В методе `call()` найти блок:

```java
if (drain) {
    return processDrain(config, workflowMode);
}

return processOnce(config, workflowMode);
```

Заменить на:

```java
try {
    if (drain) {
        return processDrain(config, workflowMode);
    }

    return processOnce(config, workflowMode);
} finally {
    applyEventRetention(config);
}
```

### Для watch

В методе `runWatch` найти блок:

```java
while (!Thread.currentThread().isInterrupted()) {
    int exitCode = processOnce(config, workflowMode);

    if (exitCode != 0 && exitCode != 3) {
        log.warn("Zenith watch iteration finished with non-zero code: {}", exitCode);
    }

    Thread.sleep(delay.toMillis());
}
```

Заменить на:

```java
while (!Thread.currentThread().isInterrupted()) {
    int exitCode = processOnce(config, workflowMode);

    if (exitCode != EXIT_OK && exitCode != EXIT_NO_EVENTS) {
        log.warn("Zenith watch iteration finished with non-zero code: {}", exitCode);
    }

    applyEventRetention(config);

    Thread.sleep(delay.toMillis());
}
```

Почему отдельно для `watch`: этот режим может работать постоянно, поэтому `finally` из `call()` выполнится только при завершении процесса. Для долгоживущего процесса очистку надо запускать после каждой итерации.

После метода `sendNotificationIfNeeded` добавить новый метод:

```java
private void applyEventRetention(ZenithConfig config) {
    EventRetentionService retentionService = new EventRetentionService();

    retentionService.apply(Path.of(config.getEvents().getRegistryUpdatedDirectory()));

    for (String directory : config.getEvents().getImportCompletedDirectories()) {
        retentionService.apply(Path.of(directory));
    }

    String checkDirectory = config.getEvents().getCheckDirectory();

    if (checkDirectory != null && !checkDirectory.isBlank()) {
        retentionService.apply(Path.of(checkDirectory));
    }
}
```

Что будет чиститься:

```text
events/registry-updated/processed
events/registry-updated/failed
events/registry-updated/results

events/zenith-imported/client-main/processed
events/zenith-imported/client-main/failed
events/zenith-imported/client-main/results

\\office-server\...\processed
\\office-server\...\failed
\\office-server\...\results
```

Если какой-то папки нет, это не ошибка: сервис просто пропустит ее.

---

## 7.6. Важное замечание по `new` и `processing`

Папки:

```text
new
processing
```

специально не чистим автоматически.

Почему:

1. `new` содержит еще не обработанные события. Удалять их по сроку опасно.
2. `processing` может содержать событие, которое осталось после аварийного завершения. Его лучше разбирать отдельно, а не молча удалять.

Автоматически удаляются только технологические следы завершенной обработки:

```text
processed
failed
results
```

---

# 9. Проверка после правок

## 9.1. Сборка

Из корня проекта:

```bat
mvn clean package
```

## 9.2. Проверка устаревшего списка

Запустить:

```bat
run-zenith-drain.bat --mode FULL
```

Ожидаемо:

```text
Zenith вернул “список неактуален”
событие перешло в processed
summary сохранен как skipped
drain продолжил следующие события
код завершения drain = 0
```

## 9.3. Проверка пустого отчета

Если в XLSX нет листа:

```text
Таблица_Проверок
```

Ожидаемо:

```text
ошибки нет
summary: processed=true, totalPersons=0, newPersons=0
уведомление: новых лиц не найдено
```

## 9.4. Проверка ошибки одного события

Искусственно положить одно некорректное событие и одно корректное.

Ожидаемо:

```text
некорректное событие -> failed
корректное событие -> processed
drain не остановился на первом сбое
```

## 9.5. Проверка логов

После сборки и запуска Zenith формат должен стать таким:

```text
2026-07-10 12:00:00 INFO  - Zenith drain completed. processedEvents=2, failedEvents=1
```

Без:

```text
org.ikozmin.zenith.ZenithProcessorMain
```

## 9.6. Проверка retention events

После истечения 30 дней старые файлы должны удаляться из:

```text
events/registry-updated/processed
events/registry-updated/failed
events/registry-updated/results

events/zenith-imported/.../processed
events/zenith-imported/.../failed
events/zenith-imported/.../results
```

Папки `new` и `processing` автоматически не удаляются.
