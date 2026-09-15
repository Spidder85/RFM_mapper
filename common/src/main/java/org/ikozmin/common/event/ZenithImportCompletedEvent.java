package org.ikozmin.common.event;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * Событие для офисного Zenith: центральный Zenith импортировал реестр, можно запускать проверку.
 */
public record ZenithImportCompletedEvent(
        String eventId,
        String eventType,
        LocalDateTime createdAt,
        String sourceEventId,
        String catalog,
        String idXml,
        Path registryFile,
        String importedAt
) {
    public static final String TYPE = "ZenithImportCompleted";
}
