package org.ikozmin.rfm.service;

import org.ikozmin.rfm.config.TelegramConfig;
import org.ikozmin.rfm.logging.Masking;
import org.ikozmin.common.notification.NotificationMessage;
import org.ikozmin.common.notification.NotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class TelegramNotificationService implements NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationService.class);
    private static final String DEFAULT_TELEGRAM_API_IP = "149.154.167.220";

    private final TelegramConfig config;

    public TelegramNotificationService(TelegramConfig config) {
        this.config = config;
    }

    @Override
    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    @Override
    public void send(NotificationMessage message) {
        if (!isEnabled()) {
            return;
        }

        try {
            validate();

            for (String chatId : config.getChatIds()) {
                if (!isBlank(chatId)) {
                    sendMessage(chatId.trim(), message.body());
                }
            }

            log.info("Telegram notification sent. chats={}", config.getChatIds().size());
        } catch (Exception e) {
            log.error("Telegram notification failed: {}", e.getMessage(), e);
        }
    }

    public void sendUpdateNotification(
            String catalogType,
            String idXml,
            Path filePath,
            String checksum,
            String oldIdXml
    ) {
        if (!isEnabled()) {
            return;
        }

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

    private void sendMessage(String chatId, String text) throws Exception {
        String apiIp = isBlank(config.getApiIp()) ? DEFAULT_TELEGRAM_API_IP : config.getApiIp().trim();
        String url = "https://api.telegram.org/bot" + config.getToken() + "/sendMessage";

        String[] command = {
                "curl.exe",
                "--silent",
                "--show-error",
                "--resolve", "api.telegram.org:443:" + apiIp,
                "--request", "POST",
                url,
                "--data-urlencode", "chat_id=" + chatId,
                "--data-urlencode", "text=" + text,
                "--data", "disable_web_page_preview=true"
        };

        log.info("Sending Telegram message. chatId={}, apiIp={}, token={}",
                chatId,
                apiIp,
                Masking.token(config.getToken()));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        int exitCode = process.waitFor();
        String response = output.toString();

        if (exitCode != 0 || !response.contains("\"ok\":true")) {
            throw new IllegalStateException("Telegram API call failed. exitCode=" + exitCode + ", response=" + response);
        }

        log.info("Telegram message delivered. chatId={}", chatId);
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
            message.append("Файл: ").append(filePath.getFileName()).append('\n');

            if (Files.exists(filePath)) {
                message.append("Размер: ").append(formatFileSize(Files.size(filePath))).append('\n');
            }
        }

        message.append('\n');
        if (!isBlank(oldIdXml)) {
            message.append("Предыдущая версия: ").append(oldIdXml).append('\n');
        }

        message.append("Новая версия: ").append(idXml).append('\n');

        if (config.isIncludeFileChecksum() && !isBlank(checksum)) {
            message.append("SHA-256: ").append(shortChecksum(checksum)).append('\n');
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
        return dateTime.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));
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
