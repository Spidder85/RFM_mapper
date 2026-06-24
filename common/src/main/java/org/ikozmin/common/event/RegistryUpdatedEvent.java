package org.ikozmin.common.event;

import java.nio.file.Path;
import java.time.LocalDateTime;

public record RegistryUpdatedEvent (
    String eventId,
    String eventType,
    LocalDateTime createdAt,
    String catalog,
    String oldIdXml,
    String idXml,
    Path registryFile,
    String sha256,
    long fileSize,
    String downloadedAt
) {
    public static final String TYPE = "RegistryUpdated";
}
