package org.ikozmin.rfm.event;

import java.nio.file.Path;

public record PublishedRegistryEvent(
        String eventId,
        Path file
) {
}
