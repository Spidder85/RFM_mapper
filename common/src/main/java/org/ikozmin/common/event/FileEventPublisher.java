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
        try {
            Files.createDirectories(newDir);

            Path tempFile = newDir.resolve(event.eventId() + ".json.tmp");
            Path finalFile = newDir.resolve(event.eventId() + ".json");

            JsonMapper.get()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(tempFile.toFile(), event);

            Files.move(tempFile, finalFile, StandardCopyOption.ATOMIC_MOVE);

            return finalFile;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish registry update event", e);
        }
    }
}
