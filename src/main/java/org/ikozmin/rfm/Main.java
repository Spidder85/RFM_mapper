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
import org.ikozmin.rfm.logging.Masking;
import org.ikozmin.rfm.model.CatalogType;
import org.ikozmin.rfm.model.Contour;
import org.ikozmin.rfm.service.NotificationService;
import org.ikozmin.rfm.service.RegistryUpdateService;
import org.ikozmin.rfm.service.UpdateResult;
import org.ikozmin.rfm.storage.RegistryStateStore;
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
    private Path configPath = Path.of("..", "config.json");

    @Option(names = {"-o", "--out"}, description = "Output directory")
    private Path outputDir = Path.of("downloads");

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
        Files.createDirectories(outputDir);

        ConfigLoader configLoader = new ConfigLoader();
        AppConfig config = configLoader.load(configPath);

        Contour contour = resolveContour(config);
        CatalogType catalogType = catalog != null
            ? CatalogType.from(catalog)
            : CatalogType.from(configLoader.defaultCatalog(config));

        log.info("Application start");
        log.info("Config path: {}", configPath.toAbsolutePath());
        log.info("Output directory: {}", outputDir.toAbsolutePath());
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

        AuditWriter auditWriter = new AuditWriter(outputDir.resolve("audit"));

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
            new RegistryStateStore(outputDir.resolve("state.properties")),
            outputDir
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

            sendNotificationIfNeeded(config, result);

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
    }

    private void sendNotificationIfNeeded(AppConfig config, UpdateResult result) {
        NotificationService notificationService = new NotificationService(config.getNotifications());

        if (!notificationService.isEnabled()) {
            return;
        }

        notificationService.sendUpdateNotification(
                result.catalogType().getCode(),
                result.idXml(),
                result.file(),
                result.sha256(),
                result.oldIdXml()
        );
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