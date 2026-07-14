package org.ikozmin.rfm.service;

import org.ikozmin.rfm.config.RetentionConfig;
import org.ikozmin.rfm.model.CatalogType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Удаляет устаревшие выгрузки и файлы аудита согласно политике хранения. */
public final class RetentionService {
    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final RetentionConfig config;

    public RetentionService(RetentionConfig config) {
        this.config = config;
    }

    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    public void apply(Path workDir, Path downloadDir, CatalogType catalogType) {
        if (!isEnabled()) {
            return;
        }

        cleanAudit(workDir.resolve("audit"));
        cleanDownloadedVersions(downloadDir, catalogType);
        cleanEmptyDirectories(downloadDir);
    }

    private void cleanAudit(Path auditDir) {
        if (!Files.isDirectory(auditDir)) {
            return;
        }

        Instant threshold = Instant.now().minus(config.getKeepAuditDays(), ChronoUnit.DAYS);

        try (Stream<Path> files = Files.list(auditDir)) {
            files
                    .filter(Files::isRegularFile)
                    .filter(file -> isOlderThan(file, threshold))
                    .forEach(this::deleteQuietly);
        } catch (Exception e) {
            log.warn("Audit retention failed. dir={}, error={}", auditDir, e.getMessage());
        }
    }

    private void cleanDownloadedVersions(Path downloadDir, CatalogType catalogType) {
        if (!Files.isDirectory(downloadDir)) {
            return;
        }

        int keep = Math.max(1, config.getKeepDownloadedVersions());
        String expectedPrefix = catalogType.getFilePrefix() + "_";
        String expectedSuffix = "." + catalogType.getExtension();

        try (Stream<Path> files = Files.walk(downloadDir)) {
            List<Path> registryFiles = files
                    .filter(Files::isRegularFile)
                    .filter(file -> {
                        String name = file.getFileName().toString();
                        return name.startsWith(expectedPrefix) && name.endsWith(expectedSuffix);
                    })
                    .sorted(Comparator.comparing(this::lastModified).reversed())
                    .toList();

            if (registryFiles.size() <= keep) {
                return;
            }

            registryFiles.stream()
                    .skip(keep)
                    .forEach(this::deleteQuietly);
        } catch (Exception e) {
            log.warn("Downloaded files retention failed. dir={}, catalog={}, error={}",
                    downloadDir,
                    catalogType.getCode(),
                    e.getMessage());
        }
    }

    private void cleanEmptyDirectories(Path root) {
        if (!Files.isDirectory(root)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(root)) {
            paths
                    .filter(Files::isDirectory)
                    .filter(path -> !path.equals(root))
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try (Stream<Path> children = Files.list(path)) {
                            if (children.findAny().isEmpty()) {
                                Files.deleteIfExists(path);
                            }
                        } catch (Exception e) {
                            log.debug("Failed to remove empty directory. dir={}, error={}", path, e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("Empty directory cleanup failed. dir={}, error={}", root, e.getMessage());
        }
    }

    private boolean isOlderThan(Path file, Instant threshold) {
        return lastModified(file).isBefore(threshold);
    }

    private Instant lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
            log.info("Retention deleted file: {}", file.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to delete file by retention. file={}, error={}", file, e.getMessage());
        }
    }
}
