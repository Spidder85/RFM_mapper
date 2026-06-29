package org.ikozmin.zenith.service;

import org.ikozmin.common.event.RegistryUpdatedEvent;
import org.ikozmin.zenith.client.ZenithApiClient;
import org.ikozmin.zenith.config.ZenithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ikozmin.zenith.state.ZenithStateStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class ZenithReportService {
    private static final Logger log = LoggerFactory.getLogger(ZenithReportService.class);

    //private static final int PODFT_REPORT_OUT_DOC_TYPE = 10217;
    private static final boolean ASSIGN_OUT_DOC_NUM = false;
    private static final long ALL_EMITENTS = -1L;
    private static final String REPORT_FORMAT = "Xlsx";

    //private static final Path REPORT_FILTER_PATH = Path.of("config", "zenith", "podft-report-filter.xml");
    private static final Path STATE_FILE = Path.of("downloads", "zenith-state.properties");

    private final ZenithStateStore stateStore;

    private final ZenithApiClient apiClient;
    private final ZenithConfig.Report config;

    public ZenithReportService(ZenithApiClient apiClient, ZenithConfig.Report config) {
        this.apiClient = apiClient;
        this.config = config;
        this.stateStore = new ZenithStateStore(STATE_FILE);
    }

    public ZenithReportResult createAndDownloadReport(RegistryUpdatedEvent event) {
        try {
            LocalDate endDate = resolveCurrentCheckDate(event);
            LocalDate beginDate = stateStore.loadLastSuccessfulCheckDate()
                    .orElse(endDate);

            String filterXml = config.isFilter() ? loadFilterXml() : null;

            int outDocType = config.getOutDocType();

            ZenithApiClient.ReportCreateData data = new ZenithApiClient.ReportCreateData(
                    outDocType,
                    ASSIGN_OUT_DOC_NUM,
                    ALL_EMITENTS,
                    beginDate.toString(),
                    endDate.toString()
            );

            ZenithApiClient.OutDocLink outDoc = apiClient.createReport(
                    data,
                    filterXml
            );

            Path outputDir = Path.of(config.getOutputDirectory());
            Files.createDirectories(outputDir);

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yy_MM_dd");
            String fileName = "T38_"
                    + beginDate.format(formatter)
                    + "-"
                    + endDate.format(formatter)
                    + "_терр"
                    //+ event.idXml()
                    + ".xlsx";

            Path targetFile = outputDir.resolve(fileName);

            apiClient.downloadOutgoingDocument(outDoc.id(), REPORT_FORMAT, targetFile);

            stateStore.saveSuccessfulCheck(endDate, event.idXml(), event.eventId());

            log.info("Zenith report downloaded. outDocId={}, file={}",
                    outDoc.id(),
                    targetFile.toAbsolutePath());

            return new ZenithReportResult(targetFile, beginDate, endDate, outDoc.id());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create and download Zenith report", e);
        }
    }

    private String loadFilterXml() throws Exception {
        Path filterPath = Path.of(config.getFilterTemplatePath());

        if (!Files.isRegularFile(filterPath)) {
            throw new IllegalStateException("Zenith report filter file not found: "
                    + filterPath.toAbsolutePath());
        }

        return Files.readString(filterPath);
    }

    private LocalDate resolveCurrentCheckDate(RegistryUpdatedEvent event) {
        if (event.downloadedAt() == null || event.downloadedAt().isBlank()) {
            return LocalDate.now();
        }

        return LocalDate.parse(event.downloadedAt().substring(0, 10));
    }
}