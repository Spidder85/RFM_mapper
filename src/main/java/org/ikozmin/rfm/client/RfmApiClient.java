package org.ikozmin.rfm.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ikozmin.rfm.exception.RfmApiException;
import org.ikozmin.rfm.exception.RfmAuthException;
import org.ikozmin.rfm.logging.Masking;
import org.ikozmin.rfm.model.AuthRequest;
import org.ikozmin.rfm.model.AuthResponse;
import org.ikozmin.rfm.model.CatalogInfo;
import org.ikozmin.rfm.model.CatalogType;
import org.ikozmin.rfm.audit.AuditEnvelope;
import org.ikozmin.rfm.audit.AuditWriter;
import java.time.LocalDateTime;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.ikozmin.rfm.model.DownloadedFile;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RfmApiClient implements RfmClient {
    private static final Logger log = LoggerFactory.getLogger(RfmApiClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SSLContext sslContext;
    private final RfmEndpoints endpoints;

    private String accessToken;

    private final ResponseValidator responseValidator;
    private final AuditWriter auditWriter;

    public RfmApiClient(HttpClient httpClient, SSLContext sslContext, RfmEndpoints endpoints, AuditWriter auditWriter) {
        this.objectMapper = new ObjectMapper();
        this.httpClient = httpClient;
        this.sslContext = sslContext;
        this.endpoints = endpoints;
        this.responseValidator = new ResponseValidator();
        this.auditWriter = auditWriter;
    }

    @Override
    public void authenticate(String userName, String password) {
        String url = endpoints.authenticateUrl();

        try {
            log.info("Authenticating user {}", Masking.userName(userName));

            AuthRequest authRequest = new AuthRequest(userName, password);
            String requestBody = objectMapper.writeValueAsString(authRequest);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofMinutes(2))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            requireSuccess(response.statusCode(), response.body(), url);

            AuthResponse authResponse = objectMapper.readValue(response.body(), AuthResponse.class);

            if (!authResponse.isSuccess()) {
                throw new RfmAuthException("Authentication rejected by service. Response: " + response.body());
            }

            if (authResponse.getValue() == null || isBlank(authResponse.getValue().getAccessToken())) {
                throw new RfmAuthException("Authentication response does not contain accessToken");
            }

            this.accessToken = authResponse.getValue().getAccessToken();

            String currentUser = authResponse.getValue().getCurrentUser() == null
                    ? "<unknown>"
                    : authResponse.getValue().getCurrentUser().getUserName();

            log.info("Authentication completed. currentUser={}, token={}",
                    currentUser,
                    Masking.token(this.accessToken));
        } catch (IOException e) {
            throw new RfmAuthException("Authentication I/O error. URL: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RfmAuthException("Authentication interrupted. URL: " + url, e);
        }
    }

    @Override
    public CatalogInfo getCatalog(CatalogType catalogType) {
        requireAuthenticated();

        String url = endpoints.catalogUrl(catalogType);

        try {
            log.info("Requesting catalog. type={}, url={}", catalogType.getCode(), url);

            EmptyBodyResponse emptyResponse = sendEmptyBodyPostWithStatus(url);

//            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
//                    .timeout(java.time.Duration.ofMinutes(2))
//                    .header("Accept", "application/json")
//                    .header("Authorization", "Bearer " + accessToken)
//                    .POST(HttpRequest.BodyPublishers.noBody())
//                    .build();
//
//            HttpResponse<String> response = httpClient.send(
//                    request,
//                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
//            );

//            requireSuccess(response.statusCode(), response.body(), url);

            writeAudit(
                    auditCatalogResponseFileName(catalogType),
                    "POST",
                    url,
                    "",
                    emptyResponse.getStatusCode(),
                    emptyResponse.getBody(),
                    "Catalog response"
            );

            CatalogInfo catalogInfo = objectMapper.readValue(emptyResponse.getBody(), CatalogInfo.class);
            String idXml = catalogInfo.requireIdXml();

            log.info("Catalog received. type={}, idXml={}, date={}, active={}",
                    catalogType.getCode(),
                    Masking.id(idXml),
                    catalogInfo.effectiveDate(),
                    catalogInfo.getIsActive());

            return catalogInfo;
        } catch (IOException e) {
            throw new RfmApiException("Catalog request I/O error. URL: " + url, -1, e.getMessage());
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//            throw new RfmApiException("Catalog request interrupted. URL: " + url, -1, e.getMessage());
        }
    }

    @Override
    public DownloadedFile downloadFile(CatalogType catalogType, String idXml, Path tempFile) {
        requireAuthenticated();

        String url = endpoints.fileUrl(catalogType);

        try {
            log.info("Downloading catalog file. type={}, idXml={}, url={}",
                    catalogType.getCode(),
                    Masking.id(idXml),
                    url
            );

            Files.createDirectories(tempFile.toAbsolutePath().getParent());

            String form = "id=" + URLEncoder.encode(idXml, StandardCharsets.UTF_8);

            writeAudit(
                    auditFileRequestFileName(catalogType),
                    "POST",
                    url,
                    form,
                    null,
                    null,
                    "File request. Binary response body is not saved"
            );

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofMinutes(5))
                    .header("Accept", expectedAcceptHeader(catalogType))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<Path> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofFile(tempFile)
            );

            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse("");

            long size = Files.exists(response.body()) ? Files.size(response.body()) : 0L;

            responseValidator.validateFileResponse(
                    catalogType,
                    response.statusCode(),
                    contentType,
                    size
            );

            log.info("Catalog file downloaded. type={}, idXml={}, path={}, bytes={}, contentType={}",
                    catalogType.getCode(),
                    Masking.id(idXml),
                    response.body().toAbsolutePath(),
                    size,
                    contentType);

            return new DownloadedFile(response.body(), contentType, size);
        } catch (IOException e) {
            throw new RfmApiException("File download I/O error. URL: " + url, -1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RfmApiException("File download interrupted. URL: " + url, -1, e.getMessage());
        }
    }

    private String expectedAcceptHeader(CatalogType catalogType) {
        if (catalogType == CatalogType.UN || catalogType == CatalogType.UN_RUS) {
            return "application/xml, application/octet-stream, */*";
        }

        return "application/zip, application/octet-stream, */*";
    }

    private void requireAuthenticated() {
        if (isBlank(accessToken)) {
            throw new RfmAuthException("Client is not authenticated");
        }
    }

    private void requireSuccess(int statusCode, String body, String url) {
        if (statusCode < 200 || statusCode >= 300) {
            throw new RfmApiException("HTTP request failed. URL: " + url, statusCode, body);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void writeAudit(
            String fileName,
            String method,
            String url,
            String requestBody,
            Integer responseStatus,
            String responseBody,
            String note
    ){
        if (auditWriter == null) {
            return;
        }

        auditWriter.write(
                fileName,
                new AuditEnvelope(
                        LocalDateTime.now().toString(),
                        method,
                        url,
                        requestBody,
                        responseStatus,
                        responseBody,
                        note
                )
        );
    }

    private String auditCatalogResponseFileName(CatalogType catalogType) {
        return switch (catalogType) {
            case TE2, TE21 -> "1_RespTE.json";
            case MVK -> "3_RespMVK.json";
            case UN -> "6_RespUN.json";
            case UN_RUS -> "7_RespUN_RUS.json";
            default -> catalogType.getCode() + "_catalog_response.json";
        };
    }

    private String auditFileRequestFileName(CatalogType catalogType) {
        return switch (catalogType) {
            case TE2, TE21 -> "2_ReqTE.json";
            case MVK -> "4_ReqMVK.json";
            case UN -> "8_ReqUN.json";
            case UN_RUS -> "9_ReqUN_RUS.json";
            default -> catalogType.getCode() + "_file_request.json";
        };
    }

    // отдельный метод для запросов с пустым телом
    private EmptyBodyResponse sendEmptyBodyPostWithStatus(String url) throws IOException {
        java.net.URL requestUrl = new java.net.URL(url);
        javax.net.ssl.HttpsURLConnection connection = (javax.net.ssl.HttpsURLConnection) requestUrl.openConnection();

        connection.setSSLSocketFactory(sslContext.getSocketFactory());

        connection.setRequestMethod("POST");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        connection.setRequestProperty("Content-Length", "0");
        connection.setDoOutput(true);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(60000);

        connection.getOutputStream().close();

        int responseCode = connection.getResponseCode();
        String responseBody;

        java.io.InputStream is = null;
        try {
            if (responseCode >= 200 && responseCode < 300) {
                is = connection.getInputStream();
            } else {
                is = connection.getErrorStream();
                // Если errorStream null, используем inputStream
                if (is == null) {
                    is = connection.getInputStream();
                }
            }

            // Если всё ещё null — тело пустое
            if (is == null) {
                responseBody = "";
            } else {
                try (java.util.Scanner scanner = new java.util.Scanner(is, StandardCharsets.UTF_8.name())) {
                    responseBody = scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
                }
            }
        } finally {
            if (is != null) {
                try { is.close(); } catch (IOException ignored) { /* игнорируем */ }
            }
        }

        if (responseCode < 200 || responseCode >= 300) {
            throw new IOException("HTTP " + responseCode + ": " + responseBody);
        }

        return new EmptyBodyResponse(responseCode, responseBody);
    }

    private static final class EmptyBodyResponse {
        private final int statusCode;
        private final String body;

        public EmptyBodyResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getBody() {
            return body;
        }
    }
}
