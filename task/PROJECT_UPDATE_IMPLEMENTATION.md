# Доработки от 2026-07-17

Инструкция относится к `zenith-processor` и общему модулю `common`. Исходный `rfm-downloader` менять не требуется.

## Решения

1. Временно недоступный Zenith не должен оставлять событие в `new`: иначе `--drain` зациклится. Такое событие переносится в `retry`; при следующем запуске автоматически возвращается в `new` для повторной попытки. Некорректные события остаются в `failed`.
2. Перед обработкой очереди сохраняется только самое позднее событие каждого перечня в каталоге `new`. Устаревшие события удаляются. Это применяется к очереди `RegistryUpdated` на сервере 2 и `ZenithImportCompleted` на сервере 3.
3. Для `IMPORT_ONLY` email-получатели могут задаваться отдельным списком `ImportTo`. Если поле отсутствует или пусто, используется обычный `To`.

## 1. Retry для временной недоступности Zenith

### 1.1. Создать RetryMetadata

Создать файл:

```text
common/src/main/java/org/ikozmin/common/event/RetryMetadata.java
```

Полный код:

```java
package org.ikozmin.common.event;

import java.time.Instant;

/**
 * Служебные сведения о следующей попытке обработки временно неуспешного события.
 */
public record RetryMetadata(
        int attempts,
        Instant nextAttemptAt,
        String lastError
) {
}
```

### 1.2. Создать EventRetryService

Создать файл:

```text
common/src/main/java/org/ikozmin/common/event/EventRetryService.java
```

Полный код:

```java
package org.ikozmin.common.event;

import org.ikozmin.common.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Управляет повторными попытками файловых событий после временных сбоев внешней системы.
 */
public final class EventRetryService {
    private static final Logger log = LoggerFactory.getLogger(EventRetryService.class);

    private static final int MAX_ATTEMPTS = 24;
    private static final String RETRY_DIRECTORY = "retry";
    private static final String METADATA_SUFFIX = ".retry.json";

    private final Path newDir;
    private final Path retryDir;

    /**
     * Создает сервис для очереди с заданным корневым каталогом.
     */
    public EventRetryService(Path rootDir) {
        this.newDir = rootDir.resolve("new");
        this.retryDir = rootDir.resolve(RETRY_DIRECTORY);
    }

    /**
     * Помещает событие в retry и возвращает false после исчерпания допустимого числа попыток.
     */
    public boolean scheduleRetry(Path processingFile, String error) {
        try {
            Files.createDirectories(retryDir);

            RetryMetadata previous = readMetadata(processingFile.getFileName().toString());
            int attempts = previous == null ? 1 : previous.attempts() + 1;

            if (attempts > MAX_ATTEMPTS) {
                log.error("Retry limit reached. file={}, attempts={}", processingFile, previous.attempts());
                return false;
            }

            RetryMetadata metadata = new RetryMetadata(
                    attempts,
                    Instant.now().plus(backoff(attempts)),
                    error == null ? "Temporary Zenith failure" : error
            );

            writeMetadata(processingFile.getFileName().toString(), metadata);
            Files.move(
                    processingFile,
                    retryDir.resolve(processingFile.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING
            );

            log.warn("Event scheduled for retry. file={}, attempts={}, nextAttemptAt={}",
                    processingFile.getFileName(),
                    attempts,
                    metadata.nextAttemptAt());

            return true;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to schedule event retry: " + processingFile, e);
        }
    }

    /**
     * Возвращает в new все события, время повторной попытки которых уже наступило.
     */
    public int requeueDueEvents() {
        if (!Files.isDirectory(retryDir)) {
            return 0;
        }

        int requeued = 0;

        try (Stream<Path> files = Files.list(retryDir)) {
            for (Path file : files
                    .filter(Files::isRegularFile)
                    .filter(this::isEventFile)
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList()) {
                RetryMetadata metadata = readMetadata(file.getFileName().toString());

                if (metadata == null || metadata.nextAttemptAt() == null
                        || metadata.nextAttemptAt().isAfter(Instant.now())) {
                    continue;
                }

                Files.createDirectories(newDir);
                Files.move(file, newDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                requeued++;

                log.info("Retry event returned to new queue. file={}, attempts={}",
                        file.getFileName(), metadata.attempts());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to requeue retry events: " + retryDir, e);
        }

        return requeued;
    }

    /**
     * Удаляет metadata после успешной или окончательно неуспешной обработки события.
     */
    public void clear(Path eventFile) {
        if (eventFile == null || eventFile.getFileName() == null) {
            return;
        }

        try {
            Files.deleteIfExists(metadataFile(eventFile.getFileName().toString()));
        } catch (Exception e) {
            log.warn("Failed to delete retry metadata. file={}, error={}", eventFile, e.getMessage());
        }
    }

    private RetryMetadata readMetadata(String eventFileName) {
        Path metadataFile = metadataFile(eventFileName);

        if (!Files.isRegularFile(metadataFile)) {
            return null;
        }

        try {
            return JsonMapper.get().readValue(metadataFile.toFile(), RetryMetadata.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read retry metadata: " + metadataFile, e);
        }
    }

    private void writeMetadata(String eventFileName, RetryMetadata metadata) {
        Path target = metadataFile(eventFileName);
        Path temporary = target.resolveSibling(target.getFileName() + ".part");

        try {
            JsonMapper.get().writeValue(temporary.toFile(), metadata);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write retry metadata: " + target, e);
        }
    }

    private Path metadataFile(String eventFileName) {
        return retryDir.resolve(eventFileName + METADATA_SUFFIX);
    }

    private boolean isEventFile(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".json") && !name.endsWith(METADATA_SUFFIX);
    }

    private Duration backoff(int attempts) {
        long minutes = Math.min(60L, 5L * (1L << Math.min(attempts - 1, 3)));
        return Duration.ofMinutes(minutes);
    }
}
```

Почему: после первой ошибки повтор будет не раньше чем через 5 минут, затем через 10, 20, 40 и далее через 60 минут. После 24 неудачных попыток событие перейдет в `failed`; при часовом запуске это примерно сутки автоматических повторов.

### 1.3. Заменить FileEventConsumer полностью

Файл:

```text
common/src/main/java/org/ikozmin/common/event/FileEventConsumer.java
```

Полный код:

```java
package org.ikozmin.common.event;

import org.ikozmin.common.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Читает очередь RegistryUpdated и переводит события между состояниями обработки.
 */
public final class FileEventConsumer {
    private final Path newDir;
    private final Path processingDir;
    private final Path processedDir;
    private final Path failedDir;
    private final EventRetryService retryService;

    public FileEventConsumer(Path rootDir) {
        this.newDir = rootDir.resolve("new");
        this.processingDir = rootDir.resolve("processing");
        this.processedDir = rootDir.resolve("processed");
        this.failedDir = rootDir.resolve("failed");
        this.retryService = new EventRetryService(rootDir);
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

                RegistryUpdatedEvent event = JsonMapper.get()
                        .readValue(target.toFile(), RegistryUpdatedEvent.class);

                return Optional.of(new ClaimedEvent(event, target));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to claim event", e);
        }
    }

    public void markProcessed(ClaimedEvent claimedEvent) {
        move(claimedEvent.file(), processedDir.resolve(claimedEvent.file().getFileName()));
        retryService.clear(claimedEvent.file());
    }

    public void markFailed(ClaimedEvent claimedEvent) {
        move(claimedEvent.file(), failedDir.resolve(claimedEvent.file().getFileName()));
        retryService.clear(claimedEvent.file());
    }

    public boolean markRetryable(ClaimedEvent claimedEvent, String error) {
        return retryService.scheduleRetry(claimedEvent.file(), error);
    }

    public int requeueDueRetries() {
        return retryService.requeueDueEvents();
    }

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

    private void move(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to move event file: " + source, e);
        }
    }

    public record ClaimedEvent(RegistryUpdatedEvent event, Path file) {
    }
}
```

### 1.4. Заменить ZenithImportCompletedEventConsumer полностью

Файл:

```text
common/src/main/java/org/ikozmin/common/event/ZenithImportCompletedEventConsumer.java
```

Полный код:

```java
package org.ikozmin.common.event;

import org.ikozmin.common.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Читает очередь ZenithImportCompleted для офисной массовой проверки.
 */
public final class ZenithImportCompletedEventConsumer {
    private final Path newDir;
    private final Path processingDir;
    private final Path processedDir;
    private final Path failedDir;
    private final EventRetryService retryService;

    public ZenithImportCompletedEventConsumer(Path rootDir) {
        this.newDir = rootDir.resolve("new");
        this.processingDir = rootDir.resolve("processing");
        this.processedDir = rootDir.resolve("processed");
        this.failedDir = rootDir.resolve("failed");
        this.retryService = new EventRetryService(rootDir);
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
        retryService.clear(claimedEvent.file());
    }

    public void markFailed(ClaimedEvent claimedEvent) {
        move(claimedEvent.file(), failedDir.resolve(claimedEvent.file().getFileName()));
        retryService.clear(claimedEvent.file());
    }

    public boolean markRetryable(ClaimedEvent claimedEvent, String error) {
        return retryService.scheduleRetry(claimedEvent.file(), error);
    }

    public int requeueDueRetries() {
        return retryService.requeueDueEvents();
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

## 2. Оставлять только последнее событие каждого перечня

### 2.1. Создать EventQueueCompactor

Создать файл:

```text
common/src/main/java/org/ikozmin/common/event/EventQueueCompactor.java
```

Полный код:

```java
package org.ikozmin.common.event;

import com.fasterxml.jackson.databind.JsonNode;
import org.ikozmin.common.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Удаляет из new устаревшие события, оставляя последнюю версию каждого перечня.
 */
public final class EventQueueCompactor {
    private static final Logger log = LoggerFactory.getLogger(EventQueueCompactor.class);

    /**
     * Компактирует очередь и возвращает количество удаленных устаревших событий.
     */
    public int keepLatestByCatalog(Path eventRootDir) {
        Path newDir = eventRootDir.resolve("new");

        if (!Files.isDirectory(newDir)) {
            return 0;
        }

        try (Stream<Path> files = Files.list(newDir)) {
            List<EventFile> events = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::readEventFile)
                    .filter(event -> event != null)
                    .toList();

            Map<String, EventFile> latestByCatalog = new HashMap<>();

            for (EventFile event : events) {
                if (event.catalog() == null || event.catalog().isBlank()) {
                    continue;
                }

                latestByCatalog.merge(
                        event.catalog(),
                        event,
                        (current, candidate) -> candidate.createdAt().isAfter(current.createdAt())
                                ? candidate
                                : current
                );
            }

            int deleted = 0;

            for (EventFile event : events) {
                if (event.catalog() == null || event.catalog().isBlank()) {
                    continue;
                }

                EventFile latest = latestByCatalog.get(event.catalog());

                if (latest != null && !latest.file().equals(event.file())) {
                    Files.deleteIfExists(event.file());
                    deleted++;

                    log.info("Obsolete queued event deleted. catalog={}, file={}, latestFile={}",
                            event.catalog(),
                            event.file().getFileName(),
                            latest.file().getFileName());
                }
            }

            return deleted;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compact event queue: " + newDir, e);
        }
    }

    private EventFile readEventFile(Path file) {
        try {
            JsonNode json = JsonMapper.get().readTree(file.toFile());
            String catalog = json.path("catalog").asText(null);
            String createdAt = json.path("createdAt").asText(null);

            Instant timestamp = createdAt == null || createdAt.isBlank()
                    ? Files.getLastModifiedTime(file).toInstant()
                    : LocalDateTime.parse(createdAt)
                    .atZone(ZoneId.systemDefault())
                    .toInstant();

            return new EventFile(file, catalog, timestamp);
        } catch (Exception e) {
            log.warn("Event will not be compacted because it cannot be read. file={}, error={}",
                    file,
                    e.getMessage());
            return null;
        }
    }

    private record EventFile(Path file, String catalog, Instant createdAt) {
    }
}
```

Почему compacting находится в `common`: обе файловые очереди имеют одинаковые поля `catalog` и `createdAt`; не нужно реализовывать одинаковое удаление в двух потребителях.

## 3. Обновить ZenithProcessorMain

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/ZenithProcessorMain.java
```

### 3.1. Добавить импорты

Добавить к импортам:

```java
import org.ikozmin.common.event.EventQueueCompactor;
import org.ikozmin.zenith.client.ZenithApiException;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
```

### 3.1.1. Удалить устаревший ручной retry

Новая очередь `retry` автоматически обрабатывает только временные сбои. Поэтому старый флаг ручного возврата всех `failed`-событий больше не нужен: он может повторно отправить в Zenith некорректное событие.

Удалить поле:

```java
@Option(names = "--retry-failed", description = "Move one failed event back to new queue before processing")
private boolean retryFailed;
```

В методе `processRegistryUpdatedEvent(...)` удалить целиком блок:

```java
if (retryFailed) {
    Optional<Path> requeued = consumer.requeueOldestFailed();

    if (requeued.isEmpty()) {
        log.info("No failed registry update events found");
        return EXIT_OK;
    }

    log.info("Failed event requeued: {}", requeued.get().toAbsolutePath());
}
```

После этого удалить неиспользуемый импорт:

```java
import java.util.Optional;
```

Не удалять `Optional`, если он все еще используется ниже в этом классе для `claimNext()`; в текущем коде он используется, поэтому импорт оставить.

### 3.2. Подготовить очередь перед обработкой

В методе `call()` после строк:

```java
ZenithConfig config = new ZenithConfigLoader().load(configPath);
ZenithWorkflowMode workflowMode = resolveMode(config);
```

добавить:

```java
prepareQueue(config, workflowMode);
```

В методе `runWatch(...)` внутри `while`, непосредственно перед:

```java
int exitCode = processOnce(config, workflowMode);
```

добавить:

```java
prepareQueue(config, workflowMode);
```

После метода `resolveMode(...)` добавить методы полностью:

```java
/**
 * Возвращает доступные retry-события в очередь и удаляет устаревшие обновления одного перечня.
 */
private void prepareQueue(ZenithConfig config, ZenithWorkflowMode workflowMode) {
    EventQueueCompactor compactor = new EventQueueCompactor();

    switch (workflowMode) {
        case FULL, IMPORT_ONLY -> {
            Path eventRootDir = Path.of(config.getEvents().getRegistryUpdatedDirectory());
            FileEventConsumer consumer = new FileEventConsumer(eventRootDir);
            int requeued = consumer.requeueDueRetries();
            int deleted = compactor.keepLatestByCatalog(eventRootDir);

            log.info("RegistryUpdated queue prepared. requeuedRetries={}, deletedObsolete={}",
                    requeued,
                    deleted);
        }
        case CHECK_ONLY -> {
            Path eventRootDir = Path.of(config.getEvents().getCheckDirectory());
            ZenithImportCompletedEventConsumer consumer = new ZenithImportCompletedEventConsumer(eventRootDir);
            int requeued = consumer.requeueDueRetries();
            int deleted = compactor.keepLatestByCatalog(eventRootDir);

            log.info("ZenithImportCompleted queue prepared. requeuedRetries={}, deletedObsolete={}",
                    requeued,
                    deleted);
        }
    }
}

/**
 * Определяет, можно ли безопасно повторить событие после ошибки Zenith.
 */
private boolean isRetryableZenithFailure(Exception exception) {
    Throwable current = exception;

    while (current != null) {
        if (current instanceof ZenithApiException apiException) {
            int status = apiException.status();
            return status == 408 || status == 429 || status >= 500;
        }

        if (current instanceof ConnectException
                || current instanceof HttpTimeoutException
                || current instanceof SocketTimeoutException
                || current instanceof UnknownHostException) {
            return true;
        }

        current = current.getCause();
    }

    return false;
}

/**
 * Создает безопасный для сотрудника summary ошибки без передачи технического текста API в уведомление.
 */
private ZenithProcessingSummary createFailureSummary(String eventId, boolean retryable) {
    String message = retryable
            ? "Zenith временно недоступен. Повторная попытка будет выполнена автоматически."
            : "Событие не обработано. Подробности доступны в журнале Zenith.";

    return ZenithProcessingSummary.failed(eventId, message);
}
```

### 3.3. Изменить catch в processRegistryUpdatedEvent

В `processRegistryUpdatedEvent(...)` заменить весь существующий блок `catch (Exception e)` на:

```java
} catch (Exception e) {
    boolean retryable = isRetryableZenithFailure(e);

    log.error("Zenith registry event failed. eventId={}, catalog={}, retryable={}, error={}",
            claimedEvent.get().event().eventId(),
            claimedEvent.get().event().catalog(),
            retryable,
            e.getMessage(),
            e);

    ZenithProcessingSummary failureSummary = createFailureSummary(
            claimedEvent.get().event().eventId(),
            retryable
    );
    saveSummary(config, failureSummary);
    notificationItems.add(new ZenithNotificationItem(
            claimedEvent.get().event().catalog(),
            failureSummary
    ));

    if (retryable && consumer.markRetryable(claimedEvent.get(), e.getMessage())) {
        return EXIT_EVENT_FAILED;
    }

    consumer.markFailed(claimedEvent.get());
    return EXIT_EVENT_FAILED;
}
```

### 3.4. Изменить catch в processImportCompletedEvent

В `processImportCompletedEvent(...)` заменить весь существующий блок `catch (Exception e)` на:

```java
} catch (Exception e) {
    boolean retryable = isRetryableZenithFailure(e);

    log.error("Zenith check event failed. eventId={}, sourceEventId={}, catalog={}, retryable={}, error={}",
            claimedEvent.get().event().eventId(),
            claimedEvent.get().event().sourceEventId(),
            claimedEvent.get().event().catalog(),
            retryable,
            e.getMessage(),
            e);

    ZenithProcessingSummary failureSummary = createFailureSummary(
            claimedEvent.get().event().sourceEventId(),
            retryable
    );
    saveSummary(config, failureSummary);
    notificationItems.add(new ZenithNotificationItem(
            claimedEvent.get().event().catalog(),
            failureSummary
    ));

    if (retryable && consumer.markRetryable(claimedEvent.get(), e.getMessage())) {
        return EXIT_EVENT_FAILED;
    }

    consumer.markFailed(claimedEvent.get());
    return EXIT_EVENT_FAILED;
}
```

## 4. Отдельные email-получатели для IMPORT_ONLY

### 4.1. Создать NotificationPurpose

Создать файл:

```text
common/src/main/java/org/ikozmin/common/notification/NotificationPurpose.java
```

Полный код:

```java
package org.ikozmin.common.notification;

/**
 * Назначение уведомления, влияющее на выбор получателей email.
 */
public enum NotificationPurpose {
    DEFAULT,
    IMPORT,
    CHECK
}
```

### 4.2. Заменить NotificationSender полностью

Файл:

```text
common/src/main/java/org/ikozmin/common/notification/NotificationSender.java
```

Полный код:

```java
package org.ikozmin.common.notification;

/**
 * Общий контракт каналов доставки уведомлений.
 */
public interface NotificationSender {
    boolean isEnabled();

    void send(NotificationMessage message);

    /**
     * Отправляет уведомление с учетом его назначения; по умолчанию канал игнорирует назначение.
     */
    default void send(NotificationMessage message, NotificationPurpose purpose) {
        send(message);
    }
}
```

### 4.3. Заменить EmailConfig полностью

Файл:

```text
common/src/main/java/org/ikozmin/common/notification/EmailConfig.java
```

Полный код:

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

    @JsonProperty("ImportTo")
    private List<String> importTo;

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

    public List<String> getRecipients(NotificationPurpose purpose) {
        if (purpose == NotificationPurpose.IMPORT && importTo != null && !importTo.isEmpty()) {
            return importTo;
        }

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

### 4.4. Заменить NotificationDispatcher полностью

Файл:

```text
common/src/main/java/org/ikozmin/common/notification/NotificationDispatcher.java
```

Полный код:

```java
package org.ikozmin.common.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Единая точка отправки уведомлений во все включенные каналы.
 */
public final class NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationsConfig config;
    private final List<NotificationSender> senders;

    public NotificationDispatcher(NotificationsConfig config) {
        this.config = config;
        this.senders = config == null
                ? List.of()
                : List.of(
                        new EmailNotificationSender(config.getEmail()),
                        new TelegramNotificationSender(config.getTelegram())
                );
    }

    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    public void send(NotificationMessage message) {
        send(message, NotificationPurpose.DEFAULT);
    }

    /**
     * Передает сообщение всем включенным каналам с учетом назначения уведомления.
     */
    public void send(NotificationMessage message, NotificationPurpose purpose) {
        if (!isEnabled()) {
            return;
        }

        long enabledSenders = 0;

        for (NotificationSender sender : senders) {
            if (sender.isEnabled()) {
                sender.send(message, purpose);
                enabledSenders++;
            }
        }

        log.info("Notification dispatch completed. purpose={}, enabledSenders={}", purpose, enabledSenders);
    }
}
```

### 4.5. Изменить EmailNotificationSender

Файл:

```text
common/src/main/java/org/ikozmin/common/notification/EmailNotificationSender.java
```

Заменить целиком методы `send(NotificationMessage message)` и `validate()` на следующие методы, а также добавить перегруженный `send`:

```java
@Override
public void send(NotificationMessage message) {
    send(message, NotificationPurpose.DEFAULT);
}

@Override
public void send(NotificationMessage message, NotificationPurpose purpose) {
    if (!isEnabled()) {
        return;
    }

    List<String> recipients = config.getRecipients(purpose);

    try {
        validate(recipients);

        Path attachment = message.attachments().isEmpty()
                ? null
                : message.attachments().getFirst();

        sendEmail(message.subject(), message.body(), recipients, attachment);

        log.info("Email notification sent. purpose={}, recipients={}", purpose, recipients.size());
    } catch (Exception e) {
        log.error("Failed to send email notification: {}", e.getMessage(), e);
    }
}

private void validate(List<String> recipients) {
    if (isBlank(config.getSmtpHost())) {
        throw new IllegalStateException("Notifications.Email.SmtpHost is empty");
    }

    if (isBlank(config.getFrom())) {
        throw new IllegalStateException("Notifications.Email.From is empty");
    }

    if (recipients == null || recipients.isEmpty()) {
        throw new IllegalStateException("Notifications.Email recipients are empty");
    }
}
```

Остальной код класса не менять.

### 4.6. Выбрать назначение уведомления в ZenithProcessorMain

В методе `sendNotificationIfNeeded(...)` заменить последнюю строку:

```java
dispatcher.send(message);
```

на:

```java
NotificationPurpose purpose = workflowMode == ZenithWorkflowMode.IMPORT_ONLY
        ? NotificationPurpose.IMPORT
        : NotificationPurpose.CHECK;

dispatcher.send(message, purpose);
```

И добавить импорт:

```java
import org.ikozmin.common.notification.NotificationPurpose;
```

## 5. Конфигурация ImportTo

Файл шаблона:

```text
zenith-processor/config/zenith-config.template.json
```

В блоке `Notifications.Email` после `To` добавить:

```json
"ImportTo": [
  "office1@your-company.ru",
  "office2@your-company.ru",
  "office3@your-company.ru"
],
```

Полный фрагмент блока:

```json
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
  "ImportTo": [
    "office1@your-company.ru",
    "office2@your-company.ru"
  ],
  "Subject": "Результат проверки Zenith",
  "IncludeAttachment": false
}
```

`ImportTo` необязателен. Если поле отсутствует или равно `[]`, `IMPORT_ONLY` отправляет письмо обычным получателям из `To`.

## 6. Проверка

```bat
mvn clean package
```

Проверить четыре сценария:

1. Остановить Zenith, запустить сервер 2: событие должно перейти в `retry`, а не в `new` или `failed`.
2. Восстановить Zenith, дождаться следующего запуска: событие возвращается в `new` и успешно обрабатывается.
3. Положить в `new` несколько событий `te21`: остается только последнее, предыдущие удаляются; для `un` и `mvk` сохраняется по одному последнему событию.
4. Запустить `IMPORT_ONLY`: email уходит адресатам `ImportTo`; удалить `ImportTo` из конфига и убедиться, что используется `To`. `CHECK_ONLY` всегда использует `To`.
