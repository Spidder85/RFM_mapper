package org.ikozmin.rfm;

import org.ikozmin.rfm.audit.AuditWriter;
import org.ikozmin.rfm.cert.CertificateLoader;
import org.ikozmin.rfm.cert.ClientCertificate;
import org.ikozmin.rfm.cert.CryptoProCertificateLoader;
import org.ikozmin.rfm.client.RetryPolicy;
import org.ikozmin.rfm.client.RfmApiClient;
import org.ikozmin.rfm.client.RfmEndpoints;
import org.ikozmin.rfm.client.RfmHttpClientFactory;
import org.ikozmin.rfm.config.AppConfig;
import org.ikozmin.rfm.config.ConfigLoader;
import org.ikozmin.rfm.event.RegistryEventService;
import org.ikozmin.rfm.logging.Masking;
import org.ikozmin.rfm.model.CatalogType;
import org.ikozmin.rfm.model.Contour;
import org.ikozmin.rfm.service.*;
import org.ikozmin.rfm.storage.RegistryStateStore;
import org.ikozmin.rfm.trigger.ZenithProcessorTrigger;
import org.ikozmin.common.event.ProcessingSummaryStore;
import org.ikozmin.common.event.ZenithProcessingSummary;
import org.ikozmin.common.notification.NotificationMessage;
import org.ikozmin.rfm.event.PublishedRegistryEvent;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;

@Command(
        name = "rfm-client",
        mixinStandardHelpOptions = true,
        description = "Downloads Rosfinmonitoring registry updates"
)
public final class Main implements Callable<Integer> {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @Option(names = {"-c", "--config"}, description = "Path to config.json")
    private Path configPath = Path.of("config", "config.json");

    @Option(names = {"-k", "--catalog"}, description = "Catalog: te2, te21, mvk, un, un-rus")
    private String catalog;

    @Option(names = "--prod", description = "Use production contour")
    private boolean prod;

    @Option(names = "--test", description = "Use test contour")
    private boolean test;

    @Option(names = "--contour", description = "Contour: prod or test")
    private String contourValue;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        try {
            run();
            return 0;
        } catch (Exception e) {
            ExitCode exitCode = ExitCode.from(e);
            log.error("Application failed. exitCode={}, error={}", exitCode, e.getMessage(), e);
            System.err.println("Application failed: " + e.getMessage());
            return exitCode.code();
        }
    }

    private void run() throws Exception {
        ConfigLoader configLoader = new ConfigLoader();
        AppConfig config = configLoader.load(configPath);

        Path workDir = Path.of("downloads");
        Files.createDirectories(workDir);

        Path downloadDir = resolveDownloadDir(config);
        Files.createDirectories(downloadDir);

        Contour contour = resolveContour(config);
        CatalogType catalogType = catalog != null
            ? CatalogType.from(catalog)
            : CatalogType.from(configLoader.defaultCatalog(config));

        log.info("Application start");
        log.info("Config path: {}", configPath.toAbsolutePath());
        log.info("Work directory: {}", workDir.toAbsolutePath());
        log.info("Download directory: {}", downloadDir.toAbsolutePath());
        log.info("Contour: {}", contour);
        log.info("Catalog: {}", catalogType.getCode());
        log.info("Certificate serial: {}", Masking.serial(configLoader.certificateSerial(config)));

        ClientCertificate certificate;

        if (config.getCertificate().isUseCryptoPro()) {
            certificate = new CryptoProCertificateLoader()
                    .load(config.getCertificate(), configLoader.certificateSerial(config));
        } else {
            certificate = new CertificateLoader()
                    .loadFromWindowsMy(configLoader.certificateSerial(config));
        }

        RfmHttpClientFactory factory = new RfmHttpClientFactory();
        HttpClient httpClient = factory.create(certificate, config.getCertificate());

        AuditWriter auditWriter = new AuditWriter(workDir.resolve("audit"));

        RfmApiClient apiClient = new RfmApiClient(
                httpClient,
                factory.getSslContext(),
                new RfmEndpoints(contour),
                auditWriter
        );

        RetryPolicy retryPolicy = new RetryPolicy(3, Duration.ofSeconds(2));

        retryPolicy.executeVoid("authenticate", () -> apiClient.authenticate(
            configLoader.userName(config),
            configLoader.password(config)
        ));

        RegistryUpdateService updateService = new RegistryUpdateService(
            apiClient,
            new RegistryStateStore(workDir.resolve("state.properties")),
            workDir,
            downloadDir
        );

        UpdateResult result = retryPolicy.execute(
                "registry-update-" + catalogType.getCode(),
                () -> updateService.update(catalogType)
        );

        if (result.isDownloaded()) {
            log.info("Update result: downloaded. catalog={}, oldIdXml={}, newIdXml={}, file={}, sha256={}, fileSize={}",
                    result.catalogType().getCode(),
                    Masking.id(result.oldIdXml()),
                    Masking.id(result.idXml()),
                    result.file(),
                    result.sha256(),
                    result.fileSize());

            PublishedRegistryEvent event = publishRegistryEvent(config, result);
            runZenithProcessorIfNeeded(config, event.file());

            Optional<ZenithProcessingSummary> zenithSummary = loadZenithSummary(config, event.eventId());
            sendNotificationIfNeeded(config, result, zenithSummary.orElse(null));
            System.out.println("UPDATED " + result.file().toAbsolutePath());
        } else {
            log.info("Update result: no updates. catalog={}, idXml={}, file={}",
                    result.catalogType().getCode(),
                    Masking.id(result.idXml()),
                    result.file());

            System.out.println("NO_UPDATES " + result.catalogType().getCode() + " " + result.idXml());

            if (result.file() != null) {
                System.out.println("CURRENT_FILE " + result.file().toAbsolutePath());
            }
        }

        applyRetentionIfNeeded(config, workDir, downloadDir, catalogType);
    }

    private PublishedRegistryEvent publishRegistryEvent(AppConfig config, UpdateResult result) {
        Path eventRootDir = Path.of(config.getEvents() == null
                ? "events/registry-updated"
                : config.getEvents().getDirectory()
        );

        RegistryEventService eventService = new RegistryEventService(eventRootDir);
        PublishedRegistryEvent publishedEvent = eventService.publish(result);

        log.info("Registry update event published: eventId={}, file={}",
                publishedEvent.eventId(),
                publishedEvent.file().toAbsolutePath());

        return publishedEvent;
    }

    private void runZenithProcessorIfNeeded(AppConfig config, Path eventFile) {
        ZenithProcessorTrigger trigger = new ZenithProcessorTrigger(config.getZenithTrigger());

        if (!trigger.isEnabled()) {
            log.info("Zenith trigger is disabled");
            return;
        }

        log.info("Zenith trigger enabled. eventFile={}", eventFile.toAbsolutePath());
        trigger.runOnce();
    }

    private Path resolveDownloadDir(AppConfig config) {
        String value = config.getOutputDirectory();

        if (value == null || value.trim().isEmpty()) {
            log.warn("OutputDirectory is not configured, using default: downloads");
            return Path.of("downloads");
        }

        return Path.of(value.trim());
    }

    private void sendNotificationIfNeeded(AppConfig config, UpdateResult result, ZenithProcessingSummary zenithSummary) {
        NotificationService notificationService = new NotificationService(config.getNotifications());

        if (!notificationService.isEnabled()) {
            return;
        }

        try {
            UnifiedNotificationTextBuilder builder = new UnifiedNotificationTextBuilder();

            NotificationMessage message = builder.build(
                    result.catalogType().getCode(),
                    result.idXml(),
                    result.oldIdXml(),
                    result.file(),
                    result.sha256(),
                    zenithSummary
            );

            notificationService.send(message);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build or send notification", e);
        }
    }

    private void applyRetentionIfNeeded(AppConfig config, Path workDir, Path downloadDir, CatalogType catalogType) {
        RetentionService retentionService = new RetentionService(config.getRetention());

        if (!retentionService.isEnabled()) {
            return;
        }

        retentionService.apply(workDir, downloadDir, catalogType);

        Path eventRootDir = Path.of(config.getEvents() == null
                ? "events/registry-updated"
                : config.getEvents().getDirectory()
        );

        new EventRetentionService(config.getRetention()).apply(eventRootDir);
    }

    private Optional<ZenithProcessingSummary> loadZenithSummary(AppConfig config, String eventId) {
        Path eventRootDir = Path.of(config.getEvents() == null
                ? "events/registry-updated"
                : config.getEvents().getDirectory()
        );

        Path summaryDir = eventRootDir.resolve("results");

        return new ProcessingSummaryStore(summaryDir).load(eventId);
    }

    private Contour resolveContour(AppConfig config) {
        int specified = 0;

        if (prod) {
            specified++;
        }

        if (test) {
            specified++;
        }

        if (contourValue != null && !contourValue.isBlank()) {
            specified++;
        }

        if (specified > 1) {
            throw new IllegalArgumentException("Use only one contour option: --prod, --test or --contour");
        }

        if (prod) {
            return Contour.PROD;
        }

        if (test) {
            return Contour.TEST;
        }

        if (contourValue != null && !contourValue.isBlank()) {
            return Contour.fromCliValue(contourValue);
        }

        return Contour.from(config.isUseTestContour());
    }
}
