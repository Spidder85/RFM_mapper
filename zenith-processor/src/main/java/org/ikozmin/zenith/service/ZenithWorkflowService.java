package org.ikozmin.zenith.service;

import org.ikozmin.common.event.RegistryUpdatedEvent;
import org.ikozmin.common.event.ZenithProcessingSummary;
import org.ikozmin.zenith.client.ZenithApiClient;
import org.ikozmin.zenith.config.ZenithConfig;
import org.ikozmin.zenith.fes.FesPackage;
import org.ikozmin.zenith.fes.FesPackageService;
import org.ikozmin.zenith.person.FoundPersonsStore;
import org.ikozmin.zenith.report.ZenithReportAnalysis;
import org.ikozmin.zenith.report.ZenithReportAnalyzer;
import org.ikozmin.zenith.report.ZenithReportPerson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ZenithWorkflowService {
    private static final Logger log = LoggerFactory.getLogger(ZenithWorkflowService.class);

    private static final String PERSON_LIST_FILE_FORMAT = "TerroristsXml";
    private static final boolean PERSON_LIST_APPEND = false;
    private static final boolean MASS_CHECK_PERIODIC = false;

    private final ZenithConfig config;
    private final ZenithApiClient apiClient;

    public ZenithWorkflowService(ZenithConfig config) {
        this.config = config;
        this.apiClient = new ZenithApiClient(config.getZenith());
    }

    public ZenithProcessingSummary process(RegistryUpdatedEvent event) {
        log.info("Processing registry update event. eventId={}, catalog={}, file={}",
                event.eventId(),
                event.catalog(),
                event.registryFile());

        importPersonListIfEnabled(event);
        runMassCheckIfEnabled();

        ZenithProcessingSummary summary = createReportIfEnabled(event);

        log.info("Zenith workflow completed. eventId={}", event.eventId());

        return summary;
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

        apiClient.runMassCheck(MASS_CHECK_PERIODIC);

        log.info("Zenith AML/CFT mass check started. periodic={}",
                MASS_CHECK_PERIODIC);
    }

    private ZenithProcessingSummary createReportIfEnabled(RegistryUpdatedEvent event) {
        ZenithConfig.Report report = config.getZenith().getReport();

        if (report == null || !report.isEnabled()) {
            log.info("Zenith report creation is disabled");
            return ZenithProcessingSummary.disabled(event.eventId());
        }

        ZenithReportService reportService = new ZenithReportService(apiClient, report);
        ZenithReportResult reportResult = reportService.createAndDownloadReport(event);

        ZenithReportAnalyzer analyzer = new ZenithReportAnalyzer();
        ZenithReportAnalysis analysis = analyzer.analyze(reportResult.reportFile());

        if (!analysis.hasPersons()) {
            log.info("No terrorist matches found in Zenith report. file={}",
                    reportResult.reportFile().toAbsolutePath());

            return ZenithProcessingSummary.noNewPersons(
                    event.eventId(),
                    reportResult.reportFile(),
                    0
            );
        }

        FoundPersonsStore personsStore = new FoundPersonsStore(
                Path.of("data", "zenith-found-persons.tsv")
        );

        List<ZenithReportPerson> newPersons = personsStore.findNewPersons(
                analysis.persons(),
                reportResult.endDate()
        );

        if (newPersons.isEmpty()) {
            log.info("Zenith report contains known persons only. totalPersons={}",
                    analysis.persons().size());
            return ZenithProcessingSummary.noNewPersons(
                    event.eventId(),
                    reportResult.reportFile(),
                    analysis.persons().size()
            );
        }

        Path fesOutputDir = Path.of(config.getZenith().getFes().getOutputDirectory());

        FesPackageService fesPackageService = new FesPackageService(fesOutputDir);
        List<FesPackage> packages = fesPackageService.preparePackages(newPersons, reportResult);

        personsStore.markFesPrepared(newPersons);

        List<ZenithProcessingSummary.Person> summaryPersons = new ArrayList<>();

        for (int i = 0; i < newPersons.size(); i++) {
            ZenithReportPerson person = newPersons.get(i);
            FesPackage pack = packages.get(i);

            summaryPersons.add(new ZenithProcessingSummary.Person(
                    person.displayName(),
                    person.accountNumber(),
                    person.emitentName(),
                    pack.directory()
            ));
        }

        log.warn("New terrorist list matches found. newPersons={}, packages={}",
                newPersons.size(),
                packages.stream().map(FesPackage::directory).toList());

        return new ZenithProcessingSummary(
                event.eventId(),
                true,
                reportResult.reportFile(),
                analysis.persons().size(),
                newPersons.size(),
                fesOutputDir,
                summaryPersons,
                "Найдены новые лица: " + newPersons.size()
        );
    }
}
