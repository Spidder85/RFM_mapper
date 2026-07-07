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
