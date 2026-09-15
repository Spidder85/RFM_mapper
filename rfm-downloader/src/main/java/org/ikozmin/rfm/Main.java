package org.ikozmin.rfm;

import org.ikozmin.common.event.ProcessingSummaryStore;
import org.ikozmin.common.event.ZenithProcessingSummary;
import org.ikozmin.common.notification.NotificationMessage;
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
import org.ikozmin.rfm.event.PublishedRegistryEvent;
import org.ikozmin.rfm.event.RegistryEventService;
import org.ikozmin.common.logging.Masking;
import org.ikozmin.rfm.model.CatalogType;
import org.ikozmin.rfm.model.Contour;
import org.ikozmin.common.event.EventRetentionService;
import org.ikozmin.common.notification.NotificationDispatcher;
import org.ikozmin.rfm.service.RegistryNotificationItem;
import org.ikozmin.rfm.service.RegistryUpdateService;
import org.ikozmin.rfm.service.RetentionService;
import org.ikozmin.rfm.service.UnifiedNotificationTextBuilder;
import org.ikozmin.rfm.service.UpdateResult;
import org.ikozmin.rfm.storage.RegistryStateStore;
import org.ikozmin.rfm.trigger.ZenithProcessorTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(
        name = "rfm-client",
        mixinStandardHelpOptions = true,
        description = "Downloads Rosfinmonitoring registry updates"
)
/**
 * Точка входа rfm-downloader: скачивает обновленные реестры, публикует события и запускает последующую обработку.
 */
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

    /** Запускает CLI и завершает процесс с кодом результата. */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    /** Выполняет команду и преобразует исключения в понятные коды завершения. */
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

    /** Выполняет один полный цикл проверки и скачивания настроенных реестров. */
    private void run() throws Exception {
        ConfigLoader configLoader = new ConfigLoader();
        AppConfig config = configLoader.load(configPath);

        Path workDir = Path.of("downloads");
        Files.createDirectories(workDir);

        Path downloadDir = resolveDownloadDir(config);
        Files.createDirectories(downloadDir);

        Map<String, String> catalogMapping = config.getOutputDirectory().getCatalogs();
        createCatalogDirectories(downloadDir, catalogMapping);

        Contour contour = resolveContour(config);
        List<CatalogType> catalogTypes = resolveCatalogs(config, configLoader);

        log.info("Application start");
        log.info("Config path: {}", configPath.toAbsolutePath());
        log.info("Work directory: {}", workDir.toAbsolutePath());
        log.info("Download directory: {}", downloadDir.toAbsolutePath());
        log.info("Contour: {}", contour);
        log.info("Catalogs: {}", catalogTypes.stream().map(CatalogType::getCode).toList());
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
            downloadDir,
            catalogMapping
        );

        List<PublishedRegistryUpdate> publishedUpdates = new ArrayList<>();

        for (CatalogType catalogType : catalogTypes) {
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
                publishedUpdates.add(new PublishedRegistryUpdate(result, event));

                System.out.println("UPDATED " + result.catalogType().getCode() + " " + result.file().toAbsolutePath());
            } else {
                log.info("Update result: no updates. catalog={}, idXml={}, file={}",
                        result.catalogType().getCode(),
                        Masking.id(result.idXml()),
                        result.file());

                System.out.println("NO_UPDATES " + result.catalogType().getCode() + " " + result.idXml());

                if (result.file() != null) {
                    System.out.println("CURRENT_FILE " + result.catalogType().getCode() + " " + result.file().toAbsolutePath());
                }
            }
        }

        runZenithProcessorIfNeeded(config, publishedUpdates);

        List<RegistryNotificationItem> notificationItems = new ArrayList<>();

        for (PublishedRegistryUpdate publishedUpdate : publishedUpdates) {
            Optional<ZenithProcessingSummary> zenithSummary = loadZenithSummary(
                    config,
                    publishedUpdate.event().eventId()
            );

            notificationItems.add(new RegistryNotificationItem(
                    publishedUpdate.result(),
                    zenithSummary.orElse(null)
            ));
        }

        sendNotificationIfNeeded(config, notificationItems);
        applyRetentionIfNeeded(config, workDir, downloadDir, catalogTypes);
    }

    /** Публикует файловое событие только для успешно скачанного реестра. */
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

    /** Запускает Zenith после появления новых реестров, если это разрешено конфигурацией. */
    private void runZenithProcessorIfNeeded(AppConfig config, List<PublishedRegistryUpdate> publishedUpdates) {
        if (publishedUpdates == null || publishedUpdates.isEmpty()) {
            return;
        }

        ZenithProcessorTrigger trigger = new ZenithProcessorTrigger(config.getZenithTrigger());

        if (!trigger.isEnabled()) {
            log.info("Zenith trigger is disabled");
            return;
        }

        boolean suppressZenithNotification = config.getZenithTrigger() != null
                && config.getZenithTrigger().isSuppressNotificationWhenRfmNotificationEnabled()
                && config.getNotifications() != null
                && config.getNotifications().isEnabled();

        log.info("Zenith trigger enabled. events={}, suppressNotification={}",
                publishedUpdates.size(),
                suppressZenithNotification);
        trigger.runOnce(suppressZenithNotification);
    }

    /** Определяет каталог выгрузки, подставляя локальный downloads при отсутствии настройки. */
    private Path resolveDownloadDir(AppConfig config) {
        AppConfig.OutputConfig output = config.getOutputDirectory();

        if (output == null || output.getPath() == null || output.getPath().trim().isEmpty()) {
            log.warn("OutputDirectory.Path is not configured, using default: downloads");
            return Path.of("downloads");
        }

        return Path.of(output.getPath().trim());
    }

    /** Возвращает пользовательское соответствие кодов реестров и имен папок. */
    private Map<String, String> resolveCatalogFolderMapping(AppConfig config) {
        AppConfig.OutputConfig output = config.getOutputDirectory();

        if (output == null || output.getCatalogs() == null) {
            return Map.of();
        }

        return output.getCatalogs();
    }

    /** Создает каталоги реестров до первой выгрузки. */
    private void createCatalogDirectories(Path downloadDir, Map<String, String> catalogMapping) throws Exception {
        for (String folderName : catalogMapping.values()) {
            if (folderName != null && !folderName.isBlank()) {
                Files.createDirectories(downloadDir.resolve(folderName));
            }
        }
    }

    /** Формирует одно итоговое уведомление по всем обновлениям текущего запуска. */
    private void sendNotificationIfNeeded(AppConfig config, List<RegistryNotificationItem> notificationItems) {
        if (notificationItems == null || notificationItems.isEmpty()) {
            return;
        }

        NotificationDispatcher notificationService = new NotificationDispatcher(config.getNotifications());

        if (!notificationService.isEnabled()) {
            return;
        }

        try {
            UnifiedNotificationTextBuilder builder = new UnifiedNotificationTextBuilder();
            NotificationMessage message = builder.build(notificationItems,config);
            notificationService.send(message);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build or send notification", e);
        }
    }

    /** Применяет правила хранения файлов, аудита и завершенных событий. */
    private void applyRetentionIfNeeded(AppConfig config, Path workDir, Path downloadDir, List<CatalogType> catalogTypes) {
        RetentionService retentionService = new RetentionService(config.getRetention());

        if (retentionService.isEnabled()) {
            for (CatalogType catalogType : catalogTypes) {
                retentionService.apply(workDir, downloadDir, catalogType);
            }
        } else {
            log.info("Registry and audit retention is disabled");
        }

        Path eventRootDir = Path.of(config.getEvents() == null
                ? "events/registry-updated"
                : config.getEvents().getDirectory()
        );

        new EventRetentionService().apply(eventRootDir);
    }

    /** Загружает результат Zenith, чтобы включить его в единое уведомление RFM. */
    private Optional<ZenithProcessingSummary> loadZenithSummary(AppConfig config, String eventId) {
        Path eventRootDir = Path.of(config.getEvents() == null
                ? "events/registry-updated"
                : config.getEvents().getDirectory()
        );

        Path summaryDir = eventRootDir.resolve("results");

        return new ProcessingSummaryStore(summaryDir).load(eventId);
    }

    /** Выбирает реестры из CLI, списка в конфигурации либо значения по умолчанию. */
    private List<CatalogType> resolveCatalogs(AppConfig config, ConfigLoader configLoader) {
        if (catalog != null && !catalog.isBlank()) {
            return List.of(CatalogType.from(catalog));
        }

        if (!config.getCatalogs().isEmpty()) {
            return config.getCatalogs()
                    .stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(CatalogType::from)
                    .distinct()
                    .toList();
        }

        return List.of(CatalogType.from(configLoader.defaultCatalog(config)));
    }

    /** Выбирает тестовый или продуктивный контур и запрещает противоречивые CLI-аргументы. */
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

    private record PublishedRegistryUpdate(
            UpdateResult result,
            PublishedRegistryEvent event
    ) {
    }
}
