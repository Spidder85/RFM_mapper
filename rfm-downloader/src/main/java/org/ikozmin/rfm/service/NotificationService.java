package org.ikozmin.rfm.service;

import org.ikozmin.rfm.config.NotificationsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationsConfig config;
    private final EmailNotificationService emailService;
    private final TelegramNotificationService telegramService;

    public NotificationService(NotificationsConfig config) {
        this.config = config;
        this.emailService = config == null ? null : new EmailNotificationService(config.getEmail());
        this.telegramService = config == null ? null : new TelegramNotificationService(config.getTelegram());
    }

    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    public void sendUpdateNotification(String catalogType, String idXml, Path filePath, String checksum, String oldIdXml) {
        if (!isEnabled()) return;

        if (emailService != null && emailService.isEnabled()) {
            emailService.sendUpdateNotification(catalogType, idXml, filePath, checksum, oldIdXml);
        }

        if (telegramService != null && telegramService.isEnabled()) {
            telegramService.sendUpdateNotification(catalogType, idXml, filePath, checksum, oldIdXml);
        }

        log.info("Notification processing completed. catalog={}, emailEnabled={}, telegramEnabled={}",
                catalogType,
                emailService != null && emailService.isEnabled(),
                telegramService != null && telegramService.isEnabled());
    }
}
