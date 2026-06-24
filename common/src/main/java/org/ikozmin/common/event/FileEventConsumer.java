package org.ikozmin.common.event;

import org.ikozmin.common.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

public final class FileEventConsumer {
    private final Path newDir;
    private final Path processingDir;
    private final Path processedDir;
    private final Path failedDir;

    public FileEventConsumer(Path rootDir) {
        this.newDir = rootDir.resolve("new");
        this.processingDir = rootDir.resolve("processing");
        this.processedDir = rootDir.resolve("processed");
        this.failedDir = rootDir.resolve("failed");
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
    }

    public void markFailed(ClaimedEvent claimedEvent) {
        move(claimedEvent.file(), failedDir.resolve(claimedEvent.file().getFileName()));
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
