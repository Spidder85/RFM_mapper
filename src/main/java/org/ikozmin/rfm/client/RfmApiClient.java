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

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public final class RfmApiClient {
    private static final Logger log = LoggerFactory.getLogger(RfmApiClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SSLContext sslContext;
    private final RfmEndpoints endpoints;

    private String accessToken;

    public RfmApiClient(HttpClient httpClient, SSLContext sslContext, RfmEndpoints endpoints) {
        this.objectMapper = new ObjectMapper();
        this.httpClient = httpClient;
        this.sslContext = sslContext;
        this.endpoints = endpoints;
    }

    public void authenticate(String userName, String password) {
        String url = endpoints.authenticateUrl();

        try {
            log.info("Authenticating user {}", userName);

            AuthRequest authRequest = new AuthRequest(userName, password);
            String requestBody = objectMapper.writeValueAsString(authRequest);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
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

    // отдельный метод для запросов с пустым телом
    private String sendEmptyBodyPost(String url) throws IOException {
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
                try { is.close(); } catch (IOException ignored) {}
            }
        }

        if (responseCode < 200 || responseCode >= 300) {
            throw new IOException("HTTP " + responseCode + ": " + responseBody);
        }

        return responseBody;
    }

    public CatalogInfo getCatalog(CatalogType catalogType) {
        requireAuthenticated();

        String url = endpoints.catalogUrl(catalogType);

        try {
            log.info("Requesting catalog. type={}, url={}", catalogType.getCode(), url);

            String responseBody = sendEmptyBodyPost(url);

//            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
//                    .header("Accept", "application/json")
//                    .header("Authorization", "Bearer " + accessToken)
//                    .POST(HttpRequest.BodyPublishers.ofString("", StandardCharsets.UTF_8))
//                    .build();
//
//            HttpResponse<String> response = httpClient.send(
//                    request,
//                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
//            );

//            requireSuccess(response.statusCode(), response.body(), url);

            CatalogInfo catalogInfo = objectMapper.readValue(responseBody, CatalogInfo.class);
            //CatalogInfo catalogInfo = objectMapper.readValue(response.body(), CatalogInfo.class);
            String idXml = catalogInfo.requireIdXml();

            log.info("Catalog received. type={}, idXml={}, date={}, active={}",
                    catalogType.getCode(),
                    idXml,
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

    public byte[] downloadFile(CatalogType catalogType, String idXml) {
        requireAuthenticated();

        String url = endpoints.fileUrl(catalogType);

        try {
            log.info("Downloading catalog file. type={}, idXml={}, url={}", catalogType.getCode(), idXml, url);

            String form = "id=" + URLEncoder.encode(idXml, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/octet-stream")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofByteArray()
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String body = new String(response.body(), StandardCharsets.UTF_8);
                throw new RfmApiException("File download failed. URL: " + url, response.statusCode(), body);
            }

            log.info("Catalog file downloaded. type={}, idXml={}, bytes={}",
                    catalogType.getCode(),
                    idXml,
                    response.body().length);

            return response.body();
        } catch (IOException e) {
            throw new RfmApiException("File download I/O error. URL: " + url, -1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RfmApiException("File download interrupted. URL: " + url, -1, e.getMessage());
        }
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
}
