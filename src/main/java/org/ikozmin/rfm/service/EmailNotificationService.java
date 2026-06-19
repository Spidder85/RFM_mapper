package org.ikozmin.rfm.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;

import org.ikozmin.rfm.config.NotificationsConfig;

public final class EmailNotificationService {
    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final NotificationsConfig config;

    public EmailNotificationService(NotificationsConfig config) {
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
            log.info("Sending email notification about registry update. catalog={}, idXml={}",
                    catalogType, idXml);

            String subject = buildSubject(catalogType, idXml);
            String body = buildBody(catalogType, idXml, filePath, checksum, oldIdXml);

            sendEmail(subject, body, config.getTo());

            log.info("Email notification sent successfully. catalog={}, recipients={}",
                    catalogType, String.join(", ", config.getTo()));
        } catch (Exception e) {
            log.error("Failed to send email notification: {}", e.getMessage(), e);
        }
    }

    private String buildSubject(String catalogType, String idXml) {

        return "";
    }

    private String buildBody(String catalogType, String idXml, Path filePath, String checksum, String oldIdXml) {

        return "";
    }

    private void sendEmail(String subject, String body, List<String> recipients) throws MessagingException {

    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.2f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.2f MB", size / (1024.0 * 1024));
        return String.format("%.2f GB", size / (1024.0 * 1024 * 1024));
    }
}
