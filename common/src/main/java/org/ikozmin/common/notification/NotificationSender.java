package org.ikozmin.common.notification;

/**
 * Общий контракт для каналов доставки уведомлений.
 */
public interface NotificationSender {
    boolean isEnabled();

    void send(NotificationMessage message);
}
