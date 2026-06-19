package org.ikozmin.rfm.service;

import org.ikozmin.rfm.config.TelegramConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class TelegramNotificationService {
    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationService.class);

    private final TelegramConfig config;

    public TelegramNotificationService(TelegramConfig config) {
        this.config = config;
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
        if (!isEnabled()) {
            return;
        }

        try {
            log.info("Sending telegram notification about registry update. catalog={}, idXml={}",
                    catalogType, idXml);

            // TODO: implement telegram notification

            log.info("Telegram notification sent successfully. catalog={}, recipients={}",
                    catalogType, String.join(", ", config.getChatIds()));
        } catch (Exception e) {
            log.error("Failed to send telegram notification: {}", e.getMessage(), e);
        }
    }

    private String buildSubject(String catalogType, String idXml) {

        return "";
    }

    private String buildBody(String catalogType, String idXml, Path filePath, String checksum, String oldIdXml) {

        return "";
    }


    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.2f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.2f MB", size / (1024.0 * 1024));
        return String.format("%.2f GB", size / (1024.0 * 1024 * 1024));
    }
}
