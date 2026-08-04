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

/** Публикует события для офисных Zenith после успешного центрального импорта реестра. */
public final class ZenithImportEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(ZenithImportEventPublisher.class);

    private final ZenithConfig.Events eventsConfig;

    public ZenithImportEventPublisher(ZenithConfig.Events eventsConfig) {
        this.eventsConfig = eventsConfig;
    }

    public List<Path> publish(RegistryUpdatedEvent sourceEvent) {
        ZenithImportCompletedEvent event = new ZenithImportCompletedEvent(
                sourceEvent.eventId() + "-imported",
                ZenithImportCompletedEvent.TYPE,
                LocalDateTime.now(),
                sourceEvent.eventId(),
                sourceEvent.catalog(),
                sourceEvent.idXml(),
                sourceEvent.registryFile(),
                LocalDateTime.now().toString()
        );

        List<Path> publishedFiles = new ArrayList<>();

        for (String directory : eventsConfig.getImportCompletedDirectories()) {
            try {
                Path file = new FileEventPublisher(
                        Path.of(directory).resolve("new")
                ).publish(event);

                publishedFiles.add(file);

                log.info(
                        "Zenith import completed event published. catalog={}, file={}",
                        event.catalog(),
                        file.toAbsolutePath()
                );
            } catch (RuntimeException e) {
                throw new ZenithImportEventPublicationException(
                        directory,
                        publishedFiles,
                        e
                );
            }
        }

        return publishedFiles;
    }
}
