package org.ikozmin.common.event;

import org.ikozmin.common.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Сохраняет и читает итог обработки Zenith по eventId.
 */
public final class ProcessingSummaryStore {
    private final Path directory;

    public ProcessingSummaryStore(Path directory) {
        this.directory = directory;
    }

    /**
     * Записывает summary, чтобы RFM мог добавить результат Zenith в итоговое уведомление.
     */
    public Path save(ZenithProcessingSummary summary) {
        try {
            Files.createDirectories(directory);

            Path file = directory.resolve(summary.eventId() + "-zenith-summary.json");
            JsonMapper.get().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), summary);

            return file;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save Zenith processing summary", e);
        }
    }

    /**
     * Загружает summary по исходному eventId, если Zenith уже обработал событие.
     */
    public Optional<ZenithProcessingSummary> load(String eventId) {
        try {
            Path file = directory.resolve(eventId + "-zenith-summary.json");

            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }

            return Optional.of(JsonMapper.get().readValue(file.toFile(), ZenithProcessingSummary.class));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load Zenith processing summary. eventId=" + eventId, e);
        }
    }
}
