package org.ikozmin.rfm.service;

import org.ikozmin.rfm.model.DownloadedFile;

import java.nio.charset.Charset;
import java.nio.file.StandardCopyOption;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.ikozmin.rfm.client.RfmClient;
import org.ikozmin.rfm.model.CatalogInfo;
import org.ikozmin.rfm.model.CatalogType;
import org.ikozmin.rfm.storage.RegistryState;
import org.ikozmin.rfm.storage.RegistryStateStore;
import org.ikozmin.rfm.logging.Masking;
import org.ikozmin.rfm.storage.Sha256;
import org.ikozmin.rfm.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class RegistryUpdateService {
    private static final Logger log = LoggerFactory.getLogger(RegistryUpdateService.class);

    private final RfmClient apiClient;
    private final RegistryStateStore stateStore;

    private final Path outputDir;   // локальная папка для audit и state
    private final Path downloadDir; // папка из конфига для скачанных файлов

    private final DownloadRequestIdResolver downloadRequestIdResolver;
    private final RegistryFileValidator registryFileValidator;

    public RegistryUpdateService(
        RfmClient apiClient,
        RegistryStateStore stateStore,
        Path outputDir,
        Path downloadDir
    ) {
        this.apiClient = apiClient;
        this.stateStore = stateStore;
        this.outputDir = outputDir;
        this.downloadDir = downloadDir;
        this.downloadRequestIdResolver = new DownloadRequestIdResolver();
        this.registryFileValidator = new RegistryFileValidator();
    }

    public UpdateResult update(CatalogType catalogType) {
        log.info("Checking registry update. catalog={}", catalogType.getCode());

        CatalogInfo remoteCatalog = apiClient.getCatalog(catalogType);
        String remoteIdXml = remoteCatalog.requireIdXml();
        String downloadRequestId = downloadRequestIdResolver.resolve(catalogType, remoteCatalog);

        RegistryState currentState = stateStore.load(catalogType);

        if (currentState != null && remoteIdXml.equalsIgnoreCase(currentState.getIdXml())) {
            log.info("Registry is actual. catalog={}, idXml={}",
                    catalogType.getCode(),
                    Masking.id(remoteIdXml)
            );

            Path currentFile = currentState.getFile() == null || currentState.getFile().trim().isEmpty()
                ? null
                : Path.of(currentState.getFile());

            return new UpdateResult(
                    false,
                    catalogType,
                    currentState.getIdXml(),
                    remoteIdXml,
                    currentFile,
                    currentState.getSha256(),
                    safeFileSize(currentFile),
                    currentState.getDownloadedAt()
            );
        }

        log.info("Registry update detected. catalog={}, oldIdXml={}, newIdXml={}",
            catalogType.getCode(),
            currentState == null ? "<none>" : Masking.id(currentState.getIdXml()),
            Masking.id(remoteIdXml)
        );

        Path savedFile = downloadAndMoveAtomically(catalogType, remoteCatalog, downloadRequestId);
        registryFileValidator.validate(catalogType, savedFile);

        // ===== РАСПАКОВЫВАЕМ ZIP (если это ZIP-архив) =====
        if (catalogType != CatalogType.UN && catalogType != CatalogType.UN_RUS) {
            unzipFile(savedFile);
        }

        String sha256 = Sha256.ofFile(savedFile);

        String oldIdXml = currentState == null ? null : currentState.getIdXml();
        String downloadedAt = LocalDateTime.now().toString();
        long fileSize = safeFileSize(savedFile);

        RegistryState newState = new RegistryState(
                remoteIdXml,
                remoteCatalog.effectiveDate(),
                savedFile.toAbsolutePath().toString(),
                downloadedAt,
                sha256
        );

        stateStore.save(catalogType, newState);

        log.info("Registry file checksum calculated. catalog={}, sha256={}",
                catalogType.getCode(),
                sha256);
        log.info("Registry update completed. catalog={}, file={}",
                catalogType.getCode(),
                savedFile.toAbsolutePath()
        );

        return new UpdateResult(
                true,
                catalogType,
                oldIdXml,
                remoteIdXml,
                savedFile,
                sha256,
                fileSize,
                downloadedAt
        );
    }

    private Path downloadAndMoveAtomically(
            CatalogType catalogType,
            CatalogInfo catalogInfo,
            String downloadRequestId
    ) {
        try {
            // ===== СОЗДАЁМ ПАПКУ С ДАТОЙ (ггммдд) В downloadDir =====
            String date = catalogInfo.effectiveDate();
            if (date == null || date.trim().isEmpty()) {
                date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                log.warn("Catalog date is empty, using current date: {}", date);
            }
            String safeDate = date.replaceAll("[^0-9A-Za-z]+", "");
            String dateFolder = safeDate.length() >= 8 ? safeDate.substring(2, 8) : safeDate;   // ггммдд

            Path dateDir = downloadDir.resolve(dateFolder);
            Files.createDirectories(dateDir);

            Path finalFile = dateDir.resolve(buildFileName(catalogType, catalogInfo));
            Path tempFile = dateDir.resolve(finalFile.getFileName().toString() + ".part");

            Files.deleteIfExists(tempFile);

            DownloadedFile downloadedFile = apiClient.downloadFile(catalogType, downloadRequestId, tempFile);

            Files.move(
                    downloadedFile.getPath(),
                    finalFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );

            log.info("Registry file saved atomically. path={}, bytes={}, contentType={}",
                    finalFile.toAbsolutePath(),
                    downloadedFile.getSize(),
                    downloadedFile.getContentType());

            return finalFile;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to download and save registry file", e);
        }
    }

    // ===== РАСПАКОВКА ZIP =====
    private void unzipFile(Path zipFile) {
        try {
            Path parentDir = zipFile.getParent();
            String zipFileName = zipFile.getFileName().toString();
            String baseName = zipFileName.substring(0, zipFileName.lastIndexOf('.'));

            try (ZipInputStream zis = new ZipInputStream(
                    Files.newInputStream(zipFile),
                    Charset.forName("CP866"))) {
                ZipEntry entry;

                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }

                    // Имя файла внутри ZIP-архива
                    String entryName = entry.getName();
                    if (entryName.contains("/")) {
                        entryName = entryName.substring(entryName.lastIndexOf('/') + 1);
                    }
                    if (entryName.contains("\\")) {
                        entryName = entryName.substring(entryName.lastIndexOf('\\') + 1);
                    }

                    Path targetFile = parentDir.resolve(entryName);

                    // Если имя внутри ZIP совпадает с именем ZIP (только расширение другое)
                    // или это XML-файл
                    Files.createDirectories(targetFile.getParent());
                    Files.copy(zis, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    log.info("Extracted file: {}", targetFile.toAbsolutePath());

                    zis.closeEntry();
                }
                log.info("ZIP file extracted successfully: {}", zipFile.toAbsolutePath());
            }
        } catch (Exception e) {
            log.error("Failed to unzip file: {}", zipFile.toAbsolutePath(), e);
            // Не выбрасываем исключение — ZIP уже скачан, распаковка не критична
        }
    }

    private String buildFileName(CatalogType catalogType, CatalogInfo catalogInfo) {
        String date = catalogInfo.effectiveDate();

        if (date == null || date.trim().isEmpty()) {
            date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        }

        String safeDate = date.replaceAll("[^0-9A-Za-z]+", "");
        String idXml = catalogInfo.requireIdXml();
        String shortId = idXml.length() > 8 ? idXml.substring(0, 8) : idXml;

        return catalogType.getFilePrefix()
            + "_"
            + safeDate
            + "_"
            + shortId
            + "."
            + catalogType.getExtension();
    }

    private long safeFileSize(Path file) {
        if (file == null || !Files.exists(file)) {
            return 0L;
        }

        try {
            return Files.size(file);
        } catch (Exception e) {
            log.warn("Failed to get file size. file={}, error={}", file, e.getMessage());
            return 0L;
        }
    }
}
