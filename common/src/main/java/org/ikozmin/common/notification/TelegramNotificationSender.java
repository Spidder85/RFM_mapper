package org.ikozmin.common.notification;

import org.ikozmin.common.logging.Masking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public final class TelegramNotificationSender implements NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationSender.class);
    private static final String DEFAULT_TELEGRAM_API_IP = "149.154.167.220";

    private final TelegramConfig config;

    public TelegramNotificationSender(TelegramConfig config) {
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

    private void validate() {
        if (isBlank(config.getToken())) {
            throw new IllegalStateException("Notifications.Telegram.Token is empty");
        }

        if (config.getChatIds() == null || config.getChatIds().isEmpty()) {
            throw new IllegalStateException("Notifications.Telegram.ChatIds is empty");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
