package org.ikozmin.rfm.service;

import org.ikozmin.rfm.model.DownloadedFile;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RegistryUpdateService {
    private static final Logger log = LoggerFactory.getLogger(RegistryUpdateService.class);

    private final RfmClient apiClient;
    private final RegistryStateStore stateStore;
    private final Path outputDir;

    private final DownloadRequestIdResolver downloadRequestIdResolver;
    private final RegistryFileValidator registryFileValidator;

    public RegistryUpdateService(
        RfmClient apiClient,
        RegistryStateStore stateStore,
        Path outputDir
    ) {
        this.apiClient = apiClient;
        this.stateStore = stateStore;
        this.outputDir = outputDir;
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

            return new UpdateResult(false, remoteIdXml, currentFile);
        }

        log.info("Registry update detected. catalog={}, oldIdXml={}, newIdXml={}",
            catalogType.getCode(),
            currentState == null ? "<none>" : Masking.id(currentState.getIdXml()),
            Masking.id(remoteIdXml)
        );

        Path savedFile = downloadAndMoveAtomically(catalogType, remoteCatalog, downloadRequestId);
        registryFileValidator.validate(catalogType, savedFile);
        String sha256 = Sha256.ofFile(savedFile);

        RegistryState newState = new RegistryState(
            remoteIdXml,
            remoteCatalog.effectiveDate(),
            savedFile.toAbsolutePath().toString(),
            LocalDateTime.now().toString(),
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

        return new UpdateResult(true, remoteIdXml, savedFile);
    }

    private Path downloadAndMoveAtomically(
            CatalogType catalogType,
            CatalogInfo catalogInfo,
            String downloadRequestId
    ) {
        try {
            Path catalogDir = outputDir.resolve(catalogType.getCode());
            Files.createDirectories(catalogDir);

            Path finalFile = catalogDir.resolve(buildFileName(catalogType, catalogInfo));
            Path tempFile = catalogDir.resolve(finalFile.getFileName().toString() + ".part");

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
}
