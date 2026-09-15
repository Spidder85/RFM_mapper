package org.ikozmin.common.notification;

/**
 * Общий контракт для каналов доставки уведомлений.
 */
public interface NotificationSender {
    boolean isEnabled();

    void send(NotificationMessage message);

    /**
     * Отправляет уведомление с учетом его назначения; по умолчанию канал игнорирует назначение.
     */
    default void send(NotificationMessage message, NotificationPurpose purpose) {
        send(message);
    }
}
