package org.ikozmin.common.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;

public final class EventRetentionService {
    private static final Logger log = LoggerFactory.getLogger(EventRetentionService.class);

    public static final int DEFAULT_KEEP_DAYS = 30;

    private static final List<String> EVENT_SUBDIRECTORIES = List.of(
            "processed",
            "failed",
            "results"
    );

    private final int keepDays;

    public EventRetentionService() {
        this(DEFAULT_KEEP_DAYS);
    }

    public EventRetentionService(int keepDays) {
        this.keepDays = Math.max(1, keepDays);
    }

    public void apply(Path eventRootDir) {
        if (eventRootDir == null) {
            return;
        }

        for (String subdirectory : EVENT_SUBDIRECTORIES) {
            clean(eventRootDir.resolve(subdirectory));
        }
    }

    private void clean(Path directory) {
        if (!Files.isDirectory(directory)) {
            return;
        }

        Instant threshold = Instant.now().minus(keepDays, ChronoUnit.DAYS);

        try (Stream<Path> files = Files.list(directory)) {
            files
                    .filter(Files::isRegularFile)
                    .filter(file -> isOlderThan(file, threshold))
                    .forEach(this::deleteQuietly);
        } catch (Exception e) {
            log.warn("Event retention failed. dir={}, keepDays={}, error={}",
                    directory,
                    keepDays,
                    e.getMessage());
        }
    }

    private boolean isOlderThan(Path file, Instant threshold) {
        try {
            return Files.getLastModifiedTime(file).toInstant().isBefore(threshold);
        } catch (Exception e) {
            return false;
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
            log.info("Old event file deleted. file={}", file.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to delete old event file. file={}, error={}",
                    file,
                    e.getMessage());
        }
    }
}
