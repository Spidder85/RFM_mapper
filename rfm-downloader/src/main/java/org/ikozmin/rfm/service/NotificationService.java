package org.ikozmin.rfm.service;

import org.ikozmin.common.notification.NotificationMessage;
import org.ikozmin.common.notification.NotificationSender;
import org.ikozmin.rfm.config.NotificationsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationsConfig config;
    private final List<NotificationSender> senders;

    public NotificationService(NotificationsConfig config) {
        this.config = config;

        if (config == null) {
            this.senders = List.of();
        } else {
            this.senders = List.of(
                    new EmailNotificationService(config.getEmail()),
                    new TelegramNotificationService(config.getTelegram())
            );
        }
    }

    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    public void send(NotificationMessage message) {
        if (!isEnabled()) {
            return;
        }

        long enabledSenders = 0;

        for (NotificationSender sender : senders) {
            if (sender.isEnabled()) {
                sender.send(message);
                enabledSenders++;
            }
        }

        log.info("Notification dispatch completed. enabledSenders={}", enabledSenders);
    }
}
