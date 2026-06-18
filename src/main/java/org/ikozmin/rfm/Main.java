package org.ikozmin.rfm;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;

import org.ikozmin.rfm.cert.CertificateLoader;
import org.ikozmin.rfm.cert.ClientCertificate;
import org.ikozmin.rfm.cert.CryptoProCertificateLoader;
import org.ikozmin.rfm.client.RfmApiClient;
import org.ikozmin.rfm.client.RfmEndpoints;
import org.ikozmin.rfm.client.RfmHttpClientFactory;
import org.ikozmin.rfm.config.AppConfig;
import org.ikozmin.rfm.config.ConfigLoader;
import org.ikozmin.rfm.logging.Masking;
import org.ikozmin.rfm.model.CatalogType;
import org.ikozmin.rfm.service.RegistryUpdateService;
import org.ikozmin.rfm.service.UpdateResult;
import org.ikozmin.rfm.storage.RegistryStateStore;
import org.ikozmin.rfm.model.Contour;
import org.ikozmin.rfm.client.RetryPolicy;
import org.ikozmin.rfm.audit.AuditWriter;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            new Main().run(args);
        } catch (Exception e) {
            ExitCode exitCode = ExitCode.from(e);

            log.error("Application failed. exitCode={}, error={}", exitCode, e.getMessage(), e);
            System.err.println("Application failed: " + e.getMessage());
            System.exit(exitCode.code());
        }
    }

    private void run(String[] args) throws Exception {
        Cli cli = Cli.parse(args);

        Path configPath = cli.configPath != null ? cli.configPath : Path.of("..", "config.json");
        Path outputDir = cli.outputDir != null ? cli.outputDir : Path.of("downloads");

        Files.createDirectories(outputDir);

        ConfigLoader configLoader = new ConfigLoader();
        AppConfig config = configLoader.load(configPath);

        Contour contour = cli.contour != null
                ? cli.contour
                : Contour.from(config.isUseTestContour());

        CatalogType catalogType = cli.catalog != null
            ? CatalogType.from(cli.catalog)
            : CatalogType.from(configLoader.defaultCatalog(config));

        log.info("Application starte");
        log.info("Config path: {}", configPath.toAbsolutePath());
        log.info("Output directory: {}", outputDir.toAbsolutePath());
        log.info("Contour: {}", contour);
        log.info("Catalog: {}", catalogType.getCode());
        log.info("Certificate serial:: {}", Masking.serial(configLoader.certificateSerial(config)));

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
            log.info("Update result: downloaded. idXml={}, file={}", result.getIdXml(), result.getFile());
            System.out.println("UPDATED " + result.getFile().toAbsolutePath());
        } else {
            log.info("Update result: no updates. idXml={}, file={}", result.getIdXml(), result.getFile());
            System.out.println("NO_UPDATES " + catalogType.getCode() + " " + result.getIdXml());

            if (result.getFile() != null) {
                System.out.println("CURRENT_FILE " + result.getFile().toAbsolutePath());
            }
        }
    }

    private static final class Cli {
        private Path configPath;
        private Path outputDir;
        private String catalog;
        private Contour contour;

        private static Cli parse(String[] args) {
            Cli cli = new Cli();

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];

                switch (arg) {
                    case "-c":
                    case "--config":
                        cli.configPath = Path.of(requireValue(args, ++i, arg));
                        break;
                    case "-o":
                    case "--out":
                        cli.outputDir = Path.of(requireValue(args, ++i, arg));
                        break;
                    case "-k":
                    case "--catalog":
                        cli.catalog = requireValue(args, ++i, arg);
                        break;
                    case "--prod":
                        cli.contour = Contour.PROD;
                        break;
                    case "--test":
                        cli.contour = Contour.TEST;
                        break;
                    case "--contour":
                        cli.contour = Contour.fromCliValue(requireValue(args, ++i, arg));
                    case "-h":
                    case "--help":
                        printHelpAndExit();
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            return cli;
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("-")) {
                throw new IllegalArgumentException("Missing value for " + option);
            }

            return args[index];
        }

        private static void printHelpAndExit() {
            System.out.println("Usage:");
            System.out.println("  java -jar target/rfm-client-1.0.0.jar [options]");
            System.out.println();
            System.out.println("Options:");
            System.out.println("  -c, --config <path>    Path to config.json. Default: ../config.json");
            System.out.println("  -o, --out <dir>        Output directory. Default: downloads");
            System.out.println("  -k, --catalog <code>   te2, te21, mvk, un, un-rus");
            System.out.println("      --prod             Use production contour");
            System.out.println("      --test             Use test contour");
            System.out.println("      --contour <value>  prod or test");
            System.out.println("  -h, --help             Show help");
            System.exit(0);
        }
    }
}