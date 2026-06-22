package org.ikozmin.rfm.service;

import org.ikozmin.rfm.config.TelegramConfig;
import org.ikozmin.rfm.logging.Masking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;

public final class TelegramNotificationService {
    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationService.class);

    private final TelegramConfig config;
    private final HttpClient httpClient;
    //private final TrustManager[] trustAllCerts;

    public TelegramNotificationService(TelegramConfig config) {
        this.config = config;
//        this.trustAllCerts = new TrustManager[]{
//                new X509TrustManager() {
//                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
//                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) { }
//                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) { }
//                }
//        };
        this.httpClient = createHttpClient();
    }

    private HttpClient createHttpClient() {
        try {
            // Создаём TrustManager, который доверяет всем сертификатам

            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                        public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) { }
                        public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) { }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            log.info("Telegram HttpClient created with Windows-ROOT truststore");
            return HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
        } catch (Exception e) {
            log.warn("Failed to create SSL context with Windows-ROOT, using default: {}", e.getMessage());
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
        }
    }

    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    public void sendUpdateNotification(
            String catalogType,
            String idXml,
            Path filePath,
            String checksum,
            String oldIdXml
    ) {
        if (!isEnabled()) return;

        try {
            validate();

            String text = buildMessage(catalogType, idXml, filePath, checksum, oldIdXml);

            for (String chatId : config.getChatIds()) {
                if (!isBlank(chatId)) {
                    sendMessage(chatId.trim(), text);
                }
            }

            log.info("Telegram notification sent. catalog={}, chats={}", catalogType, config.getChatIds().size());
        } catch (Exception e) {
            log.error("Telegram notification failed: {}", e.getMessage(), e);
        }
    }

//    private void sendMessage(String chatId, String text) throws Exception {
//        String apiUrl = "https://149.154.167.220:443/bot";
//        String url = apiUrl + config.getToken() + "/sendMessage";
//
//        String body = "chat_id=" + encode(chatId)
//                + "&text=" + encode(text)
//                + "&disable_web_page_preview=true";
//
//        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
//                .timeout(Duration.ofSeconds(30))
//                .header("Content-Type", "application/x-www-form-urlencoded")
//                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
//                .build();
//
//        HttpResponse<String> response = httpClient.send(
//                request,
//                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
//        );
//
//        if (response.statusCode() < 200 || response.statusCode() >= 300 || !response.body().contains("\"ok\":true")) {
//            throw new IllegalStateException("Telegram API error. status=" + response.statusCode() + ", body=" + response.body());
//        }
//
//        log.info("Telegram message delivered. chatId={}, token={}", chatId, Masking.token(config.getToken()));
//    }

    private void sendMessage(String chatId, String text) throws Exception {
        String token = config.getToken();
        String apiIp = config.getApiIp() != null ? config.getApiIp() : "149.154.167.220";

        // экранируем текст для curl
        String escapedText = text.replace("\"", "\\\"");

        String[] command = {
                "curl",
                "--resolve", "api.telegram.org:443:" + apiIp,
                "-X", "POST",
                "https://api.telegram.org/bot" + token + "/sendMessage",
                "-d", "chat_id=" + chatId + "&text=" + escapedText + "&disable_web_page_preview=true"
        };
        log.info("Executing: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Читаем вывод
        StringBuilder output = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        int exitCode = process.waitFor();

        if (exitCode != 0 || !output.toString().contains("\"ok\":true")) {
            throw new IllegalStateException("curl failed. exitCode=" + exitCode + ", output=" + output);
        }

        log.info("Telegram message delivered. chatId={}, output={}", chatId, output);
    }

    private String buildMessage(String catalogType, String idXml, Path filePath, String checksum, String oldIdXml) throws Exception {
        StringBuilder message = new StringBuilder();

        message.append("Обновлен перечень Росфинмониторинга").append('\n');
        message.append('\n');
        message.append("Загружена новая версия перечня.").append('\n');
        message.append('\n');

        message.append("Перечень: ").append(displayCatalogName(catalogType)).append('\n');
        message.append("Дата загрузки: ").append(formatDateTime(LocalDateTime.now())).append('\n');

        if (filePath != null) {
            message.append("Файл: ").append(filePath.toAbsolutePath()).append('\n');

            if (Files.exists(filePath)) {
                message.append("Размер: ").append(formatFileSize(Files.size(filePath))).append('\n');
            }
        }

        message.append('\n');
        message.append("idXml: ").append(idXml).append('\n');

        if (config.isIncludeFileChecksum() && !isBlank(checksum)) {
            message.append("SHA-256: ").append(checksum).append('\n');
        }

        return message.toString();
    }

    private void validate() {
        if (isBlank(config.getToken())) {
            throw new IllegalStateException("Notifications.Telegram.Token is empty");
        }

        if (config.getChatIds() == null || config.getChatIds().isEmpty()) {
            throw new IllegalStateException("Notifications.Telegram.ChatIds is empty");
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String displayCatalogName(String catalogType) {
        if (catalogType == null) {
            return "Неизвестный перечень";
        }

        return switch (catalogType.toLowerCase()) {
            case "te2", "te21" -> "Террористы и экстремисты";
            case "mvk" -> "Решения МВК";
            case "un" -> "Перечень ООН";
            case "un-rus" -> "Перечень ООН на русском языке";
            default -> catalogType;
        };
    }

    private String formatDateTime(LocalDateTime dateTime) {
        return dateTime.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));
    }

    private String shortChecksum(String checksum) {
        if (checksum == null || checksum.length() <= 16) {
            return checksum;
        }

        return checksum.substring(0, 16) + "...";
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.2f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.2f MB", size / (1024.0 * 1024));
        return String.format("%.2f GB", size / (1024.0 * 1024 * 1024));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
