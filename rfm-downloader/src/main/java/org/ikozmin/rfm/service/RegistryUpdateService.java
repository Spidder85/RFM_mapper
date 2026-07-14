package org.ikozmin.rfm.service;

import org.ikozmin.rfm.client.RfmClient;
import org.ikozmin.common.logging.Masking;
import org.ikozmin.rfm.model.CatalogInfo;
import org.ikozmin.rfm.model.CatalogType;
import org.ikozmin.rfm.model.DownloadedFile;
import org.ikozmin.rfm.storage.RegistryState;
import org.ikozmin.rfm.storage.RegistryStateStore;
import org.ikozmin.rfm.storage.Sha256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Проверяет версию реестра, скачивает обновление и фиксирует его локальное состояние. */
public final class RegistryUpdateService {
    private static final Logger log = LoggerFactory.getLogger(RegistryUpdateService.class);

    private final RfmClient apiClient;
    private final RegistryStateStore stateStore;
    private final Path workDir;
    private final Path downloadDir;
    private final DownloadRequestIdResolver downloadRequestIdResolver;
    private final RegistryFileValidator registryFileValidator;
    private final Map<String, String> catalogFolderMapping;

    /** Создает сервис обновления с хранилищем состояния и целевыми каталогами. */
    public RegistryUpdateService(
        RfmClient apiClient,
        RegistryStateStore stateStore,
        Path workDir,
        Path downloadDir,
        Map<String, String> catalogFolderMapping
    ) {
        this.apiClient = apiClient;
        this.stateStore = stateStore;
        this.workDir = workDir;
        this.downloadDir = downloadDir;
        this.catalogFolderMapping = catalogFolderMapping;
        this.downloadRequestIdResolver = new DownloadRequestIdResolver();
        this.registryFileValidator = new RegistryFileValidator();
    }

    /** Выполняет проверку и при необходимости скачивание одного типа реестра. */
    public UpdateResult update(CatalogType catalogType) {
        log.info("Checking registry update. catalog={}", catalogType.getCode());

        CatalogInfo remoteCatalog = apiClient.getCatalog(catalogType);
        String remoteIdXml = remoteCatalog.requireIdXml();
        String downloadRequestId = downloadRequestIdResolver.resolve(catalogType, remoteCatalog);

        RegistryState currentState = stateStore.load(catalogType);

        if (currentState != null && remoteIdXml.equalsIgnoreCase(currentState.getIdXml())) {
            log.info("Registry is actual. catalog={}, idXml={}",
                    catalogType.getCode(),
                    Masking.id(remoteIdXml));

            Path currentFile = currentState.getFile() == null || currentState.getFile().trim().isEmpty()
                ? null
                : Path.of(currentState.getFile());

            return new UpdateResult(
                    false,
                    catalogType,
                    currentState.getIdXml(),
                    remoteIdXml,
                    currentFile,
                    currentFile,
                    currentState.getSha256(),
                    safeFileSize(currentFile),
                    currentState.getDownloadedAt()
            );
        }

        log.info("Registry update detected. catalog={}, oldIdXml={}, newIdXml={}",
            catalogType.getCode(),
            currentState == null ? "<none>" : Masking.id(currentState.getIdXml()),
            Masking.id(remoteIdXml));

        Path savedFile = downloadAndMoveAtomically(catalogType, remoteCatalog, downloadRequestId);
        registryFileValidator.validate(catalogType, savedFile);

        Path registryFile = savedFile;

        if (catalogType != CatalogType.UN && catalogType != CatalogType.UN_RUS) {
            registryFile = unzipSingleXmlFile(savedFile);
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

        log.info("Registry file checksum calculated. catalog={}, sha256={}", catalogType.getCode(), sha256);
        log.info("Registry update completed. catalog={}, file={}", catalogType.getCode(), savedFile.toAbsolutePath());

        return new UpdateResult(
                true,
                catalogType,
                oldIdXml,
                remoteIdXml,
                registryFile,
                savedFile,
                sha256,
                fileSize,
                downloadedAt
        );
    }

    /** Скачивает файл во временное имя и атомарно публикует готовую версию. */
    private Path downloadAndMoveAtomically(
            CatalogType catalogType,
            CatalogInfo catalogInfo,
            String downloadRequestId
    ) {
        try {
            Files.createDirectories(workDir);

            String folderName = catalogFolderMapping.get(catalogType.getCode());
            if (folderName == null || folderName.isBlank()) {
                folderName = catalogType.getCode();
            }
            Path catalogDir = downloadDir.resolve(folderName);
            Path dateDir = catalogDir.resolve(resolveDateFolder(catalogInfo));
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

    private String resolveDateFolder(CatalogInfo catalogInfo) {
        String date = catalogInfo.effectiveDate();

        if (date == null || date.trim().isEmpty()) {
            date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            log.warn("Catalog date is empty, using current date: {}", date);
        }

        String safeDate = date.replaceAll("[^0-9A-Za-z]+", "");
        return safeDate.length() >= 8 ? safeDate.substring(2, 8) : safeDate;
    }

    /** Извлекает единственный XML из ZIP и защищает от небезопасных путей архива. */
    private Path unzipSingleXmlFile(Path zipFile) {
        try {
            Path parentDir = zipFile.getParent();
            Path extractedXml = null;

            try (ZipInputStream zipInputStream = new ZipInputStream(
                    Files.newInputStream(zipFile),
                    Charset.forName("CP866"))) {
                ZipEntry entry;

                while ((entry = zipInputStream.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }

                    Path targetFile = resolveSafeZipTarget(parentDir, entry.getName());
                    Files.createDirectories(targetFile.getParent());
                    Files.copy(zipInputStream, targetFile, StandardCopyOption.REPLACE_EXISTING);

                    log.info("Extracted registry file: {}", targetFile.toAbsolutePath());

                    if (targetFile.getFileName().toString().toLowerCase().endsWith(".xml")) {
                        if (extractedXml != null) {
                            throw new IllegalStateException("ZIP contains more than one XML file: " + zipFile.toAbsolutePath());
                        }
                        extractedXml = targetFile;
                    }
                    zipInputStream.closeEntry();
                }
            }

            if (extractedXml == null) {
                throw new IllegalStateException("ZIP contains no XML file: " + zipFile.toAbsolutePath());
            }

            log.info("ZIP file extracted successfully. zip={}, xml={}",
                    zipFile.toAbsolutePath(),
                    extractedXml.toAbsolutePath());

            return extractedXml;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to unzip registry XML: " + zipFile.toAbsolutePath(), e);
        }
    }

    private Path resolveSafeZipTarget(Path parentDir, String entryName) {
        String safeName = entryName.replace('\\', '/');

        if (safeName.contains("/")) {
            safeName = safeName.substring(safeName.lastIndexOf('/') + 1);
        }

        if (safeName.isBlank()) {
            throw new IllegalStateException("ZIP entry has empty file name");
        }

        Path target = parentDir.resolve(safeName).normalize();

        if (!target.startsWith(parentDir.normalize())) {
            throw new IllegalStateException("Unsafe ZIP entry path: " + entryName);
        }

        return target;
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
