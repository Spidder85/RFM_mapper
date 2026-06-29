package org.ikozmin.common.notification;

public interface NotificationSender {
    boolean isEnabled();

    void send(NotificationMessage message);
}
