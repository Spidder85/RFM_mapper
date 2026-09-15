package org.ikozmin.common.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Единая точка отправки уведомлений во все включенные каналы.
 */
public final class NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationsConfig config;
    private final List<NotificationSender> senders;

    public NotificationDispatcher(NotificationsConfig config) {
        this.config = config;
        this.senders = config == null
                ? List.of()
                : List.of(
                    new EmailNotificationSender(config.getEmail()),
                    new TelegramNotificationSender(config.getTelegram())
        );
    }

    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    /**
     * Передает сообщение каждому включенному sender-у.
     */
    public void send(NotificationMessage message) {
        send(message, NotificationPurpose.DEFAULT);
    }

    /**
     * Передает сообщение всем включенным каналам с учетом назначения уведомления.
     */
    public void send(NotificationMessage message, NotificationPurpose purpose) {
        if (!isEnabled()) {
            return;
        }

        long enabledSenders = 0;

        for (NotificationSender sender : senders) {
            if (sender.isEnabled()) {
                sender.send(message, purpose);
                enabledSenders++;
            }
        }

        log.info("Notification dispatch completed. purpose={}, enabledSenders={}", purpose, enabledSenders);
    }
}
