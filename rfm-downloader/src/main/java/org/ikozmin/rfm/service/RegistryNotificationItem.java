package org.ikozmin.rfm.service;

import org.ikozmin.common.event.ZenithProcessingSummary;

public record RegistryNotificationItem(
        UpdateResult result,
        ZenithProcessingSummary zenithSummary
) {
}
