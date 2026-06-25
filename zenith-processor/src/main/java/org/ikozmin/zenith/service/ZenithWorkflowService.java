package org.ikozmin.zenith.service;

import org.ikozmin.common.event.RegistryUpdatedEvent;
import org.ikozmin.zenith.client.ZenithApiClient;
import org.ikozmin.zenith.config.ZenithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ZenithWorkflowService {
    private static final Logger log = LoggerFactory.getLogger(ZenithWorkflowService.class);

    private static final String PERSON_LIST_FILE_FORMAT = "xml";
    private static final boolean PERSON_LIST_APPEND = false;

    private static final long ALL_EMITENTS_ID = -1L;
    private static final boolean MASS_CHECK_PERIODIC = false;

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

        importPersonListIfEnabled(event);
        runMassCheckIfEnabled();
        createReportIfEnabled(event);

        log.info("Zenith workflow completed. eventId={}", event.eventId());
    }

    private void importPersonListIfEnabled(RegistryUpdatedEvent event) {
        ZenithConfig.Import importConfig = config.getZenith().getImportConfig();

        if (importConfig != null && !importConfig.isEnabled()) {
            log.info("Zenith import step is disabled");
            return;
        }

        apiClient.importPersonList(
                event.registryFile(),
                PERSON_LIST_FILE_FORMAT,
                PERSON_LIST_APPEND
        );

        log.info("Registry list imported into Zenith. eventId={}, file={}",
                event.eventId(),
                event.registryFile());
    }

    private void runMassCheckIfEnabled() {
        ZenithConfig.MassCheck massCheck = config.getZenith().getMassCheck();

        if (massCheck != null && !massCheck.isEnabled()) {
            log.info("Zenith mass check step is disabled");
            return;
        }

        apiClient.runMassCheck(ALL_EMITENTS_ID, MASS_CHECK_PERIODIC);

        log.info("Zenith AML/CFT mass check started. emitentId={}, periodic={}",
                ALL_EMITENTS_ID,
                MASS_CHECK_PERIODIC);
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
