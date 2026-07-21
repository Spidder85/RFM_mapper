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

            return new EventFile(file,catalog, timestamp);
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
