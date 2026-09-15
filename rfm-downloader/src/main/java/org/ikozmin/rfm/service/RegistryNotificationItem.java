package org.ikozmin.rfm.service;

import org.ikozmin.common.event.ZenithProcessingSummary;

/** Данные одного обновленного реестра для итогового уведомления. */
public record RegistryNotificationItem(
        UpdateResult result,
        ZenithProcessingSummary zenithSummary
) {
}
