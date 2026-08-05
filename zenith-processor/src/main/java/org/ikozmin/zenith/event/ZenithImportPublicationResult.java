package org.ikozmin.zenith.event;

import java.util.List;

/** Хранит итог публикации служебного события по офисным очередям. */
public record ZenithImportPublicationResult(
        int destinationCount,
        List<String> failedDestinationNames
) {
    public ZenithImportPublicationResult {
        failedDestinationNames = List.copyOf(failedDestinationNames);
    }

    /** Возвращает true, когда событие не удалось записать хотя бы в одну папку офиса. */
    public boolean hasFailures() {
        return !failedDestinationNames.isEmpty();
    }
}