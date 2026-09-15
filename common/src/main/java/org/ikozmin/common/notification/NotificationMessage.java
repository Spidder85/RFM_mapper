package org.ikozmin.common.notification;

import java.nio.file.Path;
import java.util.List;

/**
 * Готовое уведомление: тема и человекочитаемый текст.
 */
public record NotificationMessage(
        String subject,
        String body,
        List<Path> attachments
) {
    public NotificationMessage(String subject, String body) {
        this(subject, body, List.of());
    }
}
