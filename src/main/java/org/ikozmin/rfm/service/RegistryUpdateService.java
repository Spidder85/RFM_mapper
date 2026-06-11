package org.ikozmin.rfm.service;

// Бизнес-логика проверки обновления и скачивания. API-клиент не знает про state и файлы, storage не знает про HTTP.

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ikozmin.rfm.client.RfmApiClient;
import org.ikozmin.rfm.model.CatalogInfo;
import org.ikozmin.rfm.model.CatalogType;
import org.ikozmin.rfm.storage.RegistryState;
import org.ikozmin.rfm.storage.RegistryStateStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class RegistryUpdateService {
    private static final Loggger log = LoggerFactory.getLogger(RegistryUpdateService.class);

    private final RfmApiClient apiClient;
    private final RegistryStateStore stateStore;
    private final Path outputDir;

    public RegistryUpdateService(
        RfmApiClient apiClient,
        RegistryStateStore stateStore,
        Path outputDir
    ) {
        this.apiClient = apiClient;
        this.stateStore = stateStore;
        this.outputDir = outputDir;
    }

    public UpdateResult update(CatalogType catalogType) {
        log.info("Проверка обновлений реестра. catalog={}", catalogType.getCode());

        CatalogInfo remoteCatalog = apiClient.getCatalogInfo(catalogType);
        String remoteIdXml = remoteCatalog.requiredIdXml();

        RegistryState currentState = stateStore.load(catalogType);

        if (currentState != null && remoteIdXml.equalsIgnoreCase(currentState.getIdXml())) {
            log.info("Реестр актуален. catalog={}, idXml={}", catalogType.getCode(), remoteIdXml);

            Path currentFile = currentState.getFile() == null || currentState.getFile().trim().isEmpty()
                ? null
                : Path.of(currentState.getFile());

            return new UpdateResult(false, remoteIdXml, currentFile);
        }

        log.info("Обнаружено обновление реестра. catalog={}, oldIdXml={}, newIdXml={}",
            catalogType.getCode(),
            currentState == null ? "<none>" : currentState.getIdXml(),
            remoteIdXml
        );

        byte[] fileBytes = apiClient.downloadFile(catalogType, remoteIdXml);
        Path savedFile = saveFile(catalogType, remoteCatalog, fileBytes);

        RegistryState newState = new RegistryState(
            remoteIdXml,
            remoteCatalog.effectiveDate(),
            savedFile.toAbsolutePath().toString(),
            LocalDateTime.now().toString()
        );

        stateStore.save(catalogType, newState);

        log.info("Обновление реестра завершено. catalog={}, file={}", catalogType.getCode(), savedFile.toAbsolutePath());

        return new UpdateResult(true, remoteIdXml, savedFile);
    }

    private Path saveFile(CatalogType catalogType, CatalogInfo catalogInfo, byte[] fileBytes) {
        try {
            Path catalogDir = outputDir.resolve(catalogType.getCode());
            Files.createDirectories(catalogDir);

            Path target = catalogDir.resolve(buildFileName(catalogInfo, catalogInfo));
            Files.write(target, fileBytes);

            log.info("Файл реестра сохранен. path={}, bytes={}", target.toAbsolutePath(), fileBytes.length);

            return target;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save registry file", e);
        }
    }

    private String buildFileName(CatalogType catalogType, CatalogInfo catalogInfo) {
        String date = catalogInfo.effectiveDate();

        if (date == null || date.trim().isEmpty()) {
            date = LocalDateTime.now()format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
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
            + catalogType.getFileExtension();
    }
}
