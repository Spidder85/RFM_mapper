package org.ikozmin.rfm.event;

import java.nio.file.Path;

/** Результат публикации события: его идентификатор и путь к JSON-файлу. */
public record PublishedRegistryEvent(
        String eventId,
        Path file
) {
}
