package org.ikozmin.rfm.service;

import org.ikozmin.rfm.config.RetentionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;

public final class EventRetentionService {
    private static final Logger log = LoggerFactory.getLogger(EventRetentionService.class);

    private final RetentionConfig config;

    public EventRetentionService(RetentionConfig config) {
        this.config = config;
    }

    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    public void apply(Path eventRootDir) {
        if (!isEnabled()) {
            return;
        }
        clean(eventRootDir.resolve("processed"), config.getKeepProcessedEventDays());
        clean(eventRootDir.resolve("results"), config.getKeepResultEventDays());
        clean(eventRootDir.resolve("failed"), config.getKeepFailedEventDays());
    }

    private void clean(Path directory, int keepDays) {
        if (!Files.isDirectory(directory)) {
            return;
        }

        Instant threshold = Instant.now().minus(keepDays, ChronoUnit.DAYS);

        try (Stream<Path> files = Files.list(directory)) {
            files
                    .filter(Files::isRegularFile)
                    .filter(file -> isOlderThen(file, threshold))
                    .forEach(this::deleteQuietly);
        } catch (Exception e) {
            log.warn("Event retention failed. dir={}, error={}", directory, e.getMessage());
        }
    }

    private boolean isOlderThen(Path file, Instant threshold) {
        try {
            return Files.getLastModifiedTime(file).toInstant().isBefore(threshold);
        } catch (Exception e) {
            return false;
        }
    }
    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
            log.info("Event retention deleted file: {}", file.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to delete event file by retention. file={}, error={}",
                    file,
                    e.getMessage());
        }
    }
}
