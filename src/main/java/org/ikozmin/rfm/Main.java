package org.ikozmin.rfm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Properties;

public final class Main {
    private static final String BASE_URL = "https://portal.fedsfm.ru:8081/Services/fedsfm-service";
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Cli cli = Cli.parse(args);

        Path configPath = cli.configPath != null ? cli.configPath : Path.of("..", "config.json");
        Path outputDir = cli.outputDir != null ? cli.outputDir : Path.of("downloads");
        boolean prod = cli.prod;
        Catalog catalog = cli.catalog != null ? Catalog.from(cli.catalog) : Catalog.TE21;

        Files.createDirectories(outputDir);

        Config config = Config.load(configPath);

        System.out.println("Config: " + configPath.toAbsolutePath());
        System.out.println("Contour: " + (prod ? "prod" : "test"));
        System.out.println("Catalog: " + catalog.code);

        KeyStore keyStore = KeyStore.getInstance("Windows-MY");
        keyStore.load(null, null);

        String certificateAlias = findCertificateAlias(keyStore, config.certificateSerialNumber);
        System.out.println("Certificate found: " + certificateAlias);

        HttpClient httpClient = createHttpClient(keyStore, certificateAlias);
        Endpoint endpoints = Endpoints.create(prod, catalog);

        Stringtoken = authenticate(httpClient, endpoints.authenticateUrl, config.userName, config.password);
        System.out.println("Authentication: OK");

        CatalogInfo remoteCatalog = getCatalog(httpClient, endpoints.catalogUrl, token);
        System.out.println("Remote date: " + remoteCatalog.date);
        System.out.println("Remote idXml: " + remoteCatalog.idXml);

        Path statePath = outputDir.resolve("state.properties");
        Properties state = loadState(statePath);

        String
    }

}