package org.ikozmin.common.event;

import java.nio.file.Path;
import java.time.LocalDateTime;

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
