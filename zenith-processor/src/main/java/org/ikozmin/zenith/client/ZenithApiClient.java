package org.ikozmin.zenith.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.ikozmin.common.json.JsonMapper;
import org.ikozmin.zenith.config.ZenithConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

public final class ZenithApiClient {
    private final ZenithConfig.Zenith config;
    private final HttpClient httpClient;

    public ZenithApiClient(ZenithConfig.Zenith config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public void importPersonList(Path file, String fileFormat, boolean append) {
        try {
            if (file == null || !Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Person list file not found: " + file);
            }

            URI uri = uri("/api/v1/opercontrol/person_lists"
                    + "?file_format=" + encode(fileFormat)
                    + "&append=" + append);

            HttpRequest request = base(uri)
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofFile(file))
                    .build();

            sendNoBody(request, "import person list");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to prepare person list import request. file=" + file, e);
        }
    }

    public void runMassCheck(boolean periodic) {
        String query = "?periodic=" + periodic;

        HttpRequest request = base(uri("/api/v1/opercontrol/aml_cft/mass_check" + query))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        sendNoBody(request, "run AML/CFT mass check");
    }

    public String getReportFilter(int outDocType) {
        HttpRequest request = base(uri("/api/v1/outgoing_documents/" + outDocType + "/filter"))
                .GET()
                .build();

        return sendString(request, "get report filter");
    }

    public OutDocLink createReport(ReportCreateData data, String filterXml) {
        String boundary = "----ZenithBoundary" + System.currentTimeMillis();

        String dataJson;
        try {
            dataJson = JsonMapper.get().writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Zenith report data", e);
        }

        MultipartBodyBuilder builder = MultipartBodyBuilder.create(boundary)
                .part("data", "application/json", dataJson.getBytes(StandardCharsets.UTF_8));

        if (filterXml != null && !filterXml.isBlank()) {
            builder.part("filter", "application/xml", filterXml.getBytes(StandardCharsets.UTF_8));
        }

        byte[] body = builder.build();

        HttpRequest request = base(uri("/api/v1/outgoing_documents/create"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        String response = sendString(request, "create report");

        try {
            JsonNode json = JsonMapper.get().readTree(response);
            return new OutDocLink(
                    json.path("id").asText(),
                    json.path("regNum").asText(),
                    json.path("regDate").asText(),
                    json.path("uid").asText(),
                    json.path("name").asText()
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse report creation response: " + response, e);
        }
    }

    public void downloadOutgoingDocument(String outDocId, String format, Path targetFile) {
        try {
            Files.createDirectories(targetFile.getParent());

            HttpRequest request = base(uri("/api/v1/outgoing_documents/" + encode(outDocId)
                    + "?format=" + encode(format)))
                    .GET()
                    .build();

            HttpResponse<Path> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofFile(targetFile)
            );

            validate(response.statusCode(), "download outgoing document", "");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to download outgoing document", e);
        }
    }

    private HttpRequest.Builder base(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMinutes(5))
                .header("Authorization", basicAuth())
                .header("Accept", "*/*");

        if (!isBlank(config.getServerName())) {
            builder.header("X-Zenith-Server", config.getServerName());
        }

        return builder;
    }

    private String sendString(HttpRequest request, String operation) {
        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            validate(response.statusCode(), operation, response.body());
            return response.body();
        } catch (Exception e) {
            throw new IllegalStateException("Zenith API call failed: " + operation, e);
        }
    }

    private void sendNoBody(HttpRequest request, String operation) {
        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            validate(response.statusCode(), operation, response.body());
        } catch (Exception e) {
            throw new IllegalStateException("Zenith API call failed: " + operation, e);
        }
    }

    private void validate(int status, String operation, String body) {
        if (status >= 200 && status < 300) {
            return;
        }

        throw new IllegalStateException("Zenith API error. operation="
                + operation
                + ", status="
                + status
                + ", body="
                + body);
    }

    private URI uri(String path) {
        String baseUrl = config.getBaseUrl();

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        return URI.create(baseUrl + path);
    }

    private String basicAuth() {
        String value = config.getUserName() + ":" + config.getPassword();
        return "Basic " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record OutDocLink(
            String id,
            String regNum,
            String regDate,
            String uid,
            String name
    ) {
    }

    public record ReportCreateData(
            int outDocType,
            boolean assignOutDocNum,
            long emitent,
            String beginDate,
            String endDate
    ){
    }
}
