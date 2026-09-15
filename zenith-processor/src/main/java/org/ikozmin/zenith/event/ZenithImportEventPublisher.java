package org.ikozmin.zenith.event;

import org.ikozmin.common.event.FileEventPublisher;
import org.ikozmin.common.event.RegistryUpdatedEvent;
import org.ikozmin.common.event.ZenithImportCompletedEvent;
import org.ikozmin.zenith.config.ZenithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Публикует офисные события после успешного центрального импорта реестра. */
public final class ZenithImportEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(ZenithImportEventPublisher.class);

    private final ZenithConfig.Events eventsConfig;

    public ZenithImportEventPublisher(ZenithConfig.Events eventsConfig) {
        this.eventsConfig = eventsConfig;
    }

    /**
     * Публикует событие в каждую офисную очередь.
     * Ошибка одного офиса фиксируется в журнале, но не отменяет другие публикации.
     */
    public ZenithImportPublicationResult publish(RegistryUpdatedEvent sourceEvent) {
        LocalDateTime createdAt = LocalDateTime.now();
        ZenithImportCompletedEvent event = new ZenithImportCompletedEvent(
                sourceEvent.eventId() + "-imported",
                ZenithImportCompletedEvent.TYPE,
                createdAt,
                sourceEvent.eventId(),
                sourceEvent.catalog(),
                sourceEvent.idXml(),
                sourceEvent.registryFile(),
                createdAt.toString()
        );

        List<ZenithConfig.Events.ImportCompletedDestination> destinations =
                eventsConfig.getImportCompletedDestinations();
        List<String> failedDestinationNames = new ArrayList<>();

        for (ZenithConfig.Events.ImportCompletedDestination destination : destinations) {
            try {
                Path file = new FileEventPublisher(
                        Path.of(destination.directory()).resolve("new")
                ).publish(event);

                log.info(
                        "Zenith import completed event published. catalog={}, destination={}, file={}",
                        event.catalog(),
                        destination.name(),
                        file.toAbsolutePath()
                );
            } catch (RuntimeException e) {
                failedDestinationNames.add(destination.name());

                log.warn(
                        "Zenith import completed event was not published. catalog={}, destination={}, directory={}, error={}",
                        event.catalog(),
                        destination.name(),
                        destination.directory(),
                        e.getMessage(),
                        e
                );
            }
        }

        return new ZenithImportPublicationResult(
                destinations.size(),
                failedDestinationNames
        );
    }
}