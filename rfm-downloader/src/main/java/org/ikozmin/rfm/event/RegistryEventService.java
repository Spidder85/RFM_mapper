package org.ikozmin.rfm.event;

import org.ikozmin.common.event.FileEventPublisher;
import org.ikozmin.common.event.RegistryUpdatedEvent;
import org.ikozmin.rfm.service.UpdateResult;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

/** Преобразует успешное обновление реестра в событие файловой очереди. */
public final class RegistryEventService {
    private final FileEventPublisher publisher;

    public RegistryEventService(Path eventRootDir) {
        this.publisher = new FileEventPublisher(eventRootDir.resolve("new"));
    }

    public PublishedRegistryEvent publish(UpdateResult result) {
        String eventId = LocalDateTime.now()
                .toString()
                .replace(":", "")
                .replace(".", "")
                + "-"
                + result.catalogType().getCode()
                + "-"
                + UUID.randomUUID();

        RegistryUpdatedEvent event = new RegistryUpdatedEvent(
                eventId,
                RegistryUpdatedEvent.TYPE,
                LocalDateTime.now(),
                result.catalogType().getCode(),
                result.oldIdXml(),
                result.idXml(),
                result.file().toAbsolutePath(),
                result.sha256(),
                result.fileSize(),
                result.downloadedAt()
        );

        return new PublishedRegistryEvent(eventId, publisher.publish(event));
    }
}
