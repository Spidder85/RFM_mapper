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
    /** Создает результат для отключенного шага формирования отчета. */
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

    /** Создает штатно пропущенный результат, например для неактуального списка. */
    public static ZenithProcessingSummary skipped(String eventId, String message) {
        return new ZenithProcessingSummary(
                eventId,
                true,
                null,
                0,
                0,
                null,
                List.of(),
                message
        );
    }

    /** Создает результат неуспешной обработки, пригодный для уведомления и диагностики. */
    public static ZenithProcessingSummary failed(String eventId, String message) {
        return new ZenithProcessingSummary(
                eventId,
                false,
                null,
                0,
                0,
                null,
                List.of(),
                message
        );
    }

    /** Создает успешный результат отчета без новых лиц для подготовки ФЭС. */
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

    /** Сведения об одном новом совпадении для уведомления и черновика ФЭС. */
    public record Person(
            String displayName,
            String accountNumber,
            String emitentName,
            Path packageDirectory
    ) {
    }
}
