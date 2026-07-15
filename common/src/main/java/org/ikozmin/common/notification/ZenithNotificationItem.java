package org.ikozmin.common.notification;

import org.ikozmin.common.event.ZenithProcessingSummary;

/**
 * Результат обработки одного перечня в составе итогового уведомления Zenith.
 */
public record ZenithNotificationItem(
        String catalog,
        ZenithProcessingSummary summary
) {
}