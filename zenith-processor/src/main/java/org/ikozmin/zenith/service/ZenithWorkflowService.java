package org.ikozmin.zenith.service;

import org.ikozmin.common.event.RegistryUpdatedEvent;
import org.ikozmin.zenith.client.ZenithApiClient;
import org.ikozmin.zenith.config.ZenithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ZenithWorkflowService {
    private static final Logger log = LoggerFactory.getLogger(ZenithWorkflowService.class);

    private final ZenithConfig config;
    private final ZenithApiClient apiClient;

    public ZenithWorkflowService(ZenithConfig config) {
        this.config = config;
        this.apiClient = new ZenithApiClient(config.getZenith());
    }

    public void process(RegistryUpdatedEvent event) {
        log.info("Processing registry update event. eventId={}, catalog={}, file={}",
                event.eventId(),
                event.catalog(),
                event.registryFile());

        importPersonList(event);
        runMassCheck();
        createReportIfEnabled(event);

        log.info("Zenith workflow completed. eventId={}", event.eventId());
    }

    private void importPersonList(RegistryUpdatedEvent event) {
        ZenithConfig.Import importConfig = config.getZenith().getImportConfig();

        String fileFormat = importConfig == null ? "xml" : importConfig.getFileFormat();
        boolean append = importConfig != null && importConfig.isAppend();

        apiClient.importPersonList(event.registryFile(), fileFormat, append);

        log.info("Registry list imported into Zenith. eventId={}, file={}",
                event.eventId(),
                event.registryFile());
    }

    private void runMassCheck() {
        ZenithConfig.MassCheck massCheck = config.getZenith().getMassCheck();

        String subsystem = massCheck == null ? null : massCheck.getSubsystem();
        String emitentId = massCheck == null ? null : massCheck.getEmitentId();
        boolean periodic = massCheck != null && massCheck.isPeriodic();

        apiClient.runMassCheck(subsystem, emitentId, periodic);

        log.info("Zenith AML/CFT mass check started");
    }

    private void createReportIfEnabled(RegistryUpdatedEvent event) {
        ZenithConfig.Report report = config.getZenith().getReport();

        if (report == null || !report.isEnabled()) {
            log.info("Zenith report creation is disabled");
            return;
        }

        ZenithReportService reportService = new ZenithReportService(apiClient, report);
        reportService.createAndDownloadReport(event);
    }
}
