package org.ikozmin.zenith.service;

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

    private static final boolean ASSIGN_OUT_DOC_NUM = false;
    private static final long ALL_EMITENTS = -1L;
    private static final String REPORT_FORMAT = "Xlsx";

    private static final Path STATE_FILE = Path.of("downloads", "zenith-state.properties");

    private final ZenithStateStore stateStore;

    private final ZenithApiClient apiClient;
    private final ZenithConfig.Report config;

    public ZenithReportService(ZenithApiClient apiClient, ZenithConfig.Report config) {
        this.apiClient = apiClient;
        this.config = config;
        this.stateStore = new ZenithStateStore(STATE_FILE);
    }

    public ZenithReportResult createAndDownloadReport(String eventId, String catalog, String idXml) {
        try {
            LocalDate endDate = LocalDate.now();
            LocalDate beginDate = stateStore.loadLastSuccessfulCheckDate(catalog)
                    .orElse(endDate);

            String filterXml = config.isFilter() ? loadFilterXml() : null;

            int outDocType = config.getOutDocType();

            log.info("Creating Zenith report. eventId={}, catalog={}, outDocType={}, beginDate={}, endDate={}, filterEnabled={}",
                    eventId,
                    catalog,
                    outDocType,
                    beginDate,
                    endDate,
                    config.isFilter());

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

            Path outputDir = resolveAppPath(config.getOutputDirectory());
            Files.createDirectories(outputDir);

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yy_MM_dd");
            String fileName = config.getFileNamePrefix()
                    + "_"
                    + beginDate.format(formatter)
                    + "-"
                    + endDate.format(formatter)
                    + "_"
                    + catalog
                    + ".xlsx";

            Path targetFile = outputDir.resolve(fileName);

            log.info("Downloading Zenith report. catalog={}, outDocId={}, targetFile={}",
                    catalog,
                    outDoc.id(),
                    targetFile.toAbsolutePath());

            apiClient.downloadOutgoingDocument(outDoc.id(), REPORT_FORMAT, targetFile);

            stateStore.saveSuccessfulCheck(catalog, endDate, idXml, eventId);

            log.info("Zenith report downloaded. catalog={}, outDocId={}, file={}",
                    catalog,
                    outDoc.id(),
                    targetFile.toAbsolutePath());

            return new ZenithReportResult(targetFile, beginDate, endDate, outDoc.id());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create and download Zenith report. catalog="
                    + catalog
                    + ", outputDirectory="
                    + config.getOutputDirectory(), e);
        }
    }

    private String loadFilterXml() throws Exception {
        Path filterPath = resolveAppPath(config.getFilterTemplatePath());

        if (!Files.isRegularFile(filterPath)) {
            throw new IllegalStateException("Zenith report filter file not found: "
                    + filterPath.toAbsolutePath());
        }

        log.info("Loading Zenith report filter: {}", filterPath.toAbsolutePath());

        return Files.readString(filterPath);
    }

    private Path resolveAppPath(String value) {
        Path path = Path.of(value);

        if (path.isAbsolute()) {
            return path.normalize();
        }

        String appHome = System.getProperty("app.home");

        if (appHome != null && !appHome.isBlank()) {
            return Path.of(appHome).resolve(path).normalize();
        }

        return Path.of(System.getProperty("user.dir")).resolve(path).normalize();
    }
}