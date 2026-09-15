package org.ikozmin.common.event;

import org.ikozmin.common.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Читает файловую очередь событий RegistryUpdated и переводит события между состояниями.
 */
public final class FileEventConsumer {
    private final Path newDir;
    private final Path processingDir;
    private final Path processedDir;
    private final Path failedDir;
    private final EventRetryService retryService;

    /** Инициализирует пути состояний очереди относительно ее корневого каталога. */
    public FileEventConsumer(Path rootDir) {
        this.newDir = rootDir.resolve("new");
        this.processingDir = rootDir.resolve("processing");
        this.processedDir = rootDir.resolve("processed");
        this.failedDir = rootDir.resolve("failed");
        this.retryService = new EventRetryService(rootDir);
    }

    /**
     * Забирает самое старое новое событие и атомарно переносит его в processing.
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

                RegistryUpdatedEvent event = JsonMapper.get()
                        .readValue(target.toFile(), RegistryUpdatedEvent.class);

                return Optional.of(new ClaimedEvent(event, target));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to claim event", e);
        }
    }

    /**
     * Помечает событие успешно обработанным.
     */
    public void markProcessed(ClaimedEvent claimedEvent) {
        move(claimedEvent.file(), processedDir.resolve(claimedEvent.file().getFileName()));
        retryService.clear(claimedEvent.file());
    }

    /**
     * Помечает событие ошибочным для ручного разбора или повторной обработки.
     */
    public void markFailed(ClaimedEvent claimedEvent) {
        move(claimedEvent.file(), failedDir.resolve(claimedEvent.file().getFileName()));
        retryService.clear(claimedEvent.file());
    }

    /* помечает событие необходимым для повторной обработки */
    public boolean markRetryable(ClaimedEvent claimedEvent, String error) {
        return retryService.scheduleRetry(claimedEvent.file(), error);
    }

    /* перемещает события с истекшим сроком повторной обработки в очередь new */
    public int requeueDueRetries() {
        return  retryService.requeueDueEvents();
    }

    /** Перемещает файл события в следующее состояние очереди. */
    private void move(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to move event file: " + source, e);
        }
    }

    /** Захваченное событие и его файл в каталоге processing. */
    public record ClaimedEvent(RegistryUpdatedEvent event, Path file) {
    }

    /**
     * Возвращает самое старое failed-событие обратно в очередь new.
     */
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
}
