package org.ikozmin.zenith.service;

import org.ikozmin.common.event.RegistryUpdatedEvent;
import org.ikozmin.common.event.ZenithImportCompletedEvent;
import org.ikozmin.common.event.ZenithProcessingSummary;
import org.ikozmin.zenith.client.ZenithApiClient;
import org.ikozmin.zenith.config.ZenithConfig;
import org.ikozmin.zenith.event.ZenithImportEventPublisher;
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

    private final ZenithConfig config;
    private final ZenithApiClient apiClient;

    public ZenithWorkflowService(ZenithConfig config) {
        this.config = config;
        this.apiClient = new ZenithApiClient(config.getZenith());
    }

    public ZenithProcessingSummary processFull(RegistryUpdatedEvent event) {
        log.info("Processing full Zenith workflow. eventId={}, catalog={}, file={}",
                event.eventId(),
                event.catalog(),
                event.registryFile());

        importPersonListIfEnabled(event);
        runMassCheckIfEnabled(event.catalog());

        ZenithProcessingSummary summary = createReportIfEnabled(
                event.eventId(),
                event.catalog(),
                event.idXml()
        );

        log.info("Full Zenith workflow completed. eventId={}", event.eventId());

        return summary;
    }

    public ZenithProcessingSummary processImportOnly(RegistryUpdatedEvent event) {
        log.info("Processing Zenith import only. eventId={}, catalog={}, file={}",
                event.eventId(),
                event.catalog(),
                event.registryFile());

        importPersonListIfEnabled(event);
        new ZenithImportEventPublisher(config.getEvents()).publish(event);

        return new ZenithProcessingSummary(
                event.eventId(),
                true,
                null,
                0,
                0,
                null,
                List.of(),
                "Реестр импортирован в Zenith. Каталог: " + event.catalog()
        );
    }

    public ZenithProcessingSummary processCheckOnly(ZenithImportCompletedEvent event) {
        log.info("Processing Zenith check only. eventId={}, sourceEventId={}, catalog={}",
                event.eventId(),
                event.sourceEventId(),
                event.catalog());

        runMassCheckIfEnabled(event.catalog());

        ZenithProcessingSummary summary = createReportIfEnabled(
                event.sourceEventId(),
                event.catalog(),
                event.idXml()
        );

        log.info("Zenith check workflow completed. eventId={}", event.eventId());

        return summary;
    }

    private void importPersonListIfEnabled(RegistryUpdatedEvent event) {
        ZenithConfig.Import importConfig = config.getZenith().getImportConfig();

        if (importConfig != null && !importConfig.isEnabled()) {
            log.info("Zenith import step is disabled");
            return;
        }

        String fileFormat = importConfig == null
                ? "TerroristsXml"
                : importConfig.getFileFormat();

        boolean append = importConfig != null && importConfig.isAppend()

        apiClient.importPersonList(
                event.registryFile(),
                fileFormat,
                append
        );

        log.info("Registry list imported into Zenith. eventId={}, catalog={}, file={}",
                event.eventId(),
                event.catalog(),
                event.registryFile());
    }

    private void runMassCheckIfEnabled(String catalog) {
        ZenithConfig.MassCheck massCheck = config.getZenith().getMassCheck();

        if (massCheck != null && !massCheck.isEnabled()) {
            log.info("Zenith mass check step is disabled");
            return;
        }

        boolean periodic = massCheck != null && massCheck.isPeriodic();

        apiClient.runMassCheck(periodic);

        log.info("Zenith AML/CFT mass check started. catalog={}, periodic={}",
                catalog,
                periodic);
    }

    private ZenithProcessingSummary createReportIfEnabled(String eventId, String catalog, String idXml) {
        ZenithConfig.Report report = config.getZenith().getReport(catalog);

        if (report == null || !report.isEnabled()) {
            log.info("Zenith report creation is disabled. catalog={}", catalog);
            return ZenithProcessingSummary.disabled(eventId);
        }

        ZenithReportService reportService = new ZenithReportService(apiClient, report);
        ZenithReportResult reportResult = reportService.createAndDownloadReport(eventId, catalog, idXml);

        ZenithReportAnalyzer analyzer = new ZenithReportAnalyzer();
        ZenithReportAnalysis analysis = analyzer.analyze(reportResult.reportFile());

        if (!analysis.hasPersons()) {
            log.info("No matches found in Zenith report. catalog={}, file={}",
                    catalog,
                    reportResult.reportFile().toAbsolutePath());

            return ZenithProcessingSummary.noNewPersons(
                    eventId,
                    reportResult.reportFile(),
                    0
            );
        }

        FoundPersonsStore personsStore = new FoundPersonsStore(
                Path.of("data", "zenith-found-persons.tsv")
        );

        List<ZenithReportPerson> newPersons = personsStore.findNewPersons(
                catalog,
                analysis.persons(),
                reportResult.endDate()
        );

        if (newPersons.isEmpty()) {
            log.info("Zenith report contains known persons only. catalog={}, totalPersons={}",
                    catalog,
                    analysis.persons().size());

            return ZenithProcessingSummary.noNewPersons(
                    eventId,
                    reportResult.reportFile(),
                    analysis.persons().size()
            );
        }

        Path fesOutputDir = Path.of(config.getZenith().getFes().getOutputDirectory());

        FesPackageService fesPackageService = new FesPackageService(fesOutputDir);
        List<FesPackage> packages = fesPackageService.preparePackages(newPersons, reportResult);

        personsStore.markFesPrepared(catalog, newPersons);

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

        log.warn("New list matches found. catalog={}, newPersons={}, packages={}",
                catalog,
                newPersons.size(),
                packages.stream().map(FesPackage::directory).toList());

        return new ZenithProcessingSummary(
                eventId,
                true,
                reportResult.reportFile(),
                analysis.persons().size(),
                newPersons.size(),
                fesOutputDir,
                summaryPersons,
                "Найдены новые лица. Перечень: " + catalog + ", количество: " + newPersons.size()
        );
    }
}
