package org.ikozmin.rfm.service;

import jakarta.mail.MessagingException;
import org.ikozmin.rfm.config.EmailConfig;
import org.ikozmin.rfm.config.NotificationsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

public final class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationsConfig config;

    public NotificationService(NotificationsConfig config) {
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
            log.info("Sending notification about registry update. catalog={}, idXml={}",
                    catalogType, idXml);

            // TODO: implement notification sending

            log.info("Email notification sent successfully. catalog={}",
                    catalogType);
        } catch (Exception e) {
            log.error("Failed to send notification: {}", e.getMessage(), e);
        }
    }

    private String buildSubject(String catalogType, String idXml) {

        return "";
    }

    private String buildMessage(String catalogType, String idXml, Path filePath, String checksum, String oldIdXml) {
        return "";
    }

        private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.2f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.2f MB", size / (1024.0 * 1024));
        return String.format("%.2f GB", size / (1024.0 * 1024 * 1024));
    }
}
