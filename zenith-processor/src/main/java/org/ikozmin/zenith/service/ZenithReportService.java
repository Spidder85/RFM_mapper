package org.ikozmin.zenith.service;

import org.ikozmin.common.event.RegistryUpdatedEvent;
import org.ikozmin.zenith.client.ZenithApiClient;
import org.ikozmin.zenith.config.ZenithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

public final class ZenithReportService {
    private static final Logger log = LoggerFactory.getLogger(ZenithReportService.class);

    private final ZenithApiClient apiClient;
    private final ZenithConfig.Report config;

    public ZenithReportService(ZenithApiClient apiClient, ZenithConfig.Report config) {
        this.apiClient = apiClient;
        this.config = config;
    }

    public Path createAndDownloadReport(RegistryUpdatedEvent event) {
        try {
            String filterXml = loadFilterXml(event);

            ZenithApiClient.OutDocLink outDoc = apiClient.createReport(
                    config.getOutDocType(),
                    config.isAssignOutDocNum(),
                    filterXml
            );

            Path outputDir = Path.of(config.getOutputDirectory());
            Files.createDirectories(outputDir);

            String fileName = "T38_"
                    + LocalDate.now()
                    + "_"
                    + event.idXml()
                    + "."
                    + config.getFormat().toLowerCase();

            Path targetFile = outputDir.resolve(fileName);

            apiClient.downloadOutgoingDocument(outDoc.id(), config.getFormat(), targetFile);

            log.info("Zenith report downloaded. outDocId={}, file={}",
                    outDoc.id(),
                    targetFile.toAbsolutePath());

            return targetFile;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create and download Zenith report", e);
        }
    }

    private String loadFilterXml(RegistryUpdatedEvent event) throws Exception {
        if (config.getFilterTemplatePath() == null || config.getFilterTemplatePath().isBlank()) {
            return apiClient.getReportFilter(config.getOutDocType());
        }

        String template = Files.readString(Path.of(config.getFilterTemplatePath()));

        return template
                .replace("${BEGIN_DATE}", resolveBeginDate(event))
                .replace("${END_DATE}", LocalDate.now().toString());
    }

    private String resolveBeginDate(RegistryUpdatedEvent event) {
        if (event.downloadedAt() == null || event.downloadedAt().isBlank()) {
            return LocalDate.now().toString();
        }

        return event.downloadedAt().substring(0, 10);
    }
}