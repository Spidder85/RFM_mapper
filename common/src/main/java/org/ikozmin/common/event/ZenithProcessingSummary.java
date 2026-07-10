package org.ikozmin.common.event;

import java.nio.file.Path;
import java.util.List;

/**
 * Краткий результат обработки события в Zenith для уведомлений и связи RFM с Zenith.
 */
public record ZenithProcessingSummary(
        String eventId,
        boolean processed,
        Path reportFile,
        int totalPersons,
        int newPersons,
        Path fesPackageRoot,
        List<Person> persons,
        String message
) {
    public static ZenithProcessingSummary disabled(String eventId) {
        return new ZenithProcessingSummary(
                eventId,
                true,
                null,
                0,
                0,
                null,
                List.of(),
                "Отчет Zenith отключен в конфигурации"
        );
    }

    public static ZenithProcessingSummary noNewPersons(String eventId, Path reportFile, int totalPersons) {
        return new ZenithProcessingSummary(
                eventId,
                true,
                reportFile,
                totalPersons,
                0,
                null,
                List.of(),
                "Новых лиц не найдено"
        );
    }

    public record Person(
            String displayName,
            String accountNumber,
            String emitentName,
            Path packageDirectory
    ) {
    }
}
