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
        Path metadataFile  = metadataFile(eventFileName);

        if (!Files.isRegularFile(metadataFile )) {
            return null;
        }

        try {
            return JsonMapper.get().readValue(metadataFile.toFile(),RetryMetadata.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read retry metadata: " + metadataFile, e);
        }
    }

    private void writeMetadata(String eventFileName, RetryMetadata metadata) {
        Path target = metadataFile(eventFileName);
        Path temporary = target.resolveSibling(target.getFileName() + ",part");

        try {
            JsonMapper.get().writeValue(temporary.toFile(), metadata);
            Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);
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
