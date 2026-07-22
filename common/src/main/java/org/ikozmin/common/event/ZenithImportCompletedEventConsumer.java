package org.ikozmin.common.event;

import org.ikozmin.common.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Читает файловую очередь событий ZenithImportCompleted для офисной проверки.
 */
public final class ZenithImportCompletedEventConsumer {
    private final Path newDir;
    private final Path processingDir;
    private final Path processedDir;
    private final Path failedDir;
    private final EventRetryService retryService;

    /** Инициализирует пути состояний офисной очереди относительно ее корня. */
    public ZenithImportCompletedEventConsumer(Path rootDir) {
        this.newDir = rootDir.resolve("new");
        this.processingDir = rootDir.resolve("processing");
        this.processedDir = rootDir.resolve("processed");
        this.failedDir = rootDir.resolve("failed");
        this.retryService = new EventRetryService(rootDir);
    }

    /**
     * Забирает следующее событие офисной очереди и переносит его в processing.
     */
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

    /**
     * Помечает офисное событие успешно обработанным.
     */
    public void markProcessed(ClaimedEvent claimedEvent) {
        move(claimedEvent.file(), processedDir.resolve(claimedEvent.file().getFileName()));
        retryService.clear(claimedEvent.file());
    }

    /**
     * Помечает офисное событие ошибочным для последующего разбора.
     */
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

    /** Перемещает событие между состояниями офисной файловой очереди. */
    private void move(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to move event file: " + source, e);
        }
    }

    /** Захваченное офисное событие и его файл в processing. */
    public record ClaimedEvent(ZenithImportCompletedEvent event, Path file) {
    }
}
