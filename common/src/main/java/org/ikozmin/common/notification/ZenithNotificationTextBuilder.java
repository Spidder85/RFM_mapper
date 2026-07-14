package org.ikozmin.common.notification;

import org.ikozmin.common.event.ZenithProcessingSummary;

import java.nio.file.Path;

/**
 * Формирует человекочитаемый текст результатов Zenith для standalone и RFM-уведомлений.
 */
public final class ZenithNotificationTextBuilder {
    /**
     * Собирает отдельное уведомление, когда Zenith запускается автономно.
     */
    public NotificationMessage buildStandalone(String catalog, ZenithProcessingSummary summary) {
        String subject = "Результат проверки Zenith: " + displayCatalogName(catalog);
        String lineSeparator = System.lineSeparator();
        StringBuilder body = new StringBuilder();

        body.append("Здравствуйте.").append(lineSeparator);
        body.append(lineSeparator);
        body.append("Завершена проверка в Zenith.").append(lineSeparator);
        body.append(lineSeparator);
        body.append("Перечень: ").append(displayCatalogName(catalog)).append(lineSeparator);

        appendReportFile(body, summary, lineSeparator);

        body.append(lineSeparator);
        appendResultBlock(body, "", summary);
        body.append(lineSeparator);
        body.append("Это автоматическое уведомление. Не надо на него отвечать.").append(lineSeparator);

        return new NotificationMessage(subject, body.toString());
    }

    /**
     * Добавляет Zenith-блок внутрь общего уведомления RFM.
     */
    public void appendEmbeddedBlock(StringBuilder body, String indent, ZenithProcessingSummary summary) {
        body.append(System.lineSeparator());
        body.append(indent).append("Проверка в Zenith").append(System.lineSeparator());
        appendResultBlock(body, indent + indent, summary);
    }

    /** Добавляет в текст понятный результат: ошибку, пустой отчет либо найденных лиц. */
    private void appendResultBlock(StringBuilder body, String indent, ZenithProcessingSummary summary) {
        String lineSeparator = System.lineSeparator();

        if (summary == null) {
            body.append(indent)
                    .append("Результат Zenith недоступен. Проверьте журнал zenith-processor и каталог events.")
                    .append(lineSeparator);
            return;
        }

        if (!summary.processed()) {
            body.append(indent)
                    .append("Проверка в Zenith завершилась ошибкой.")
                    .append(lineSeparator);
            body.append(indent)
                    .append("Причина: ")
                    .append(value(summary.message()))
                    .append(lineSeparator);
            return;
        }

        if (summary.reportFile() == null && summary.totalPersons() == 0 && summary.newPersons() == 0
                && summary.message() != null && !summary.message().isBlank()) {
            body.append(indent)
                    .append(summary.message())
                    .append(lineSeparator);
            return;
        }

        if (summary.reportFile() != null) {
            body.append(indent)
                    .append("Отчет: ")
                    .append(normalize(summary.reportFile()))
                    .append(lineSeparator);
        }

        if (summary.newPersons() <= 0) {
            body.append(indent)
                    .append("Новых лиц не найдено.")
                    .append(lineSeparator);
            body.append(indent)
                    .append("Всего совпадений в отчете: ")
                    .append(summary.totalPersons())
                    .append(lineSeparator);
            return;
        }

        body.append(indent)
                .append("Найдены новые лица: ")
                .append(summary.newPersons())
                .append(lineSeparator);
        body.append(lineSeparator);

        for (int i = 0; i < summary.persons().size(); i++) {
            ZenithProcessingSummary.Person person = summary.persons().get(i);

            body.append(indent)
                    .append(i + 1)
                    .append(". ")
                    .append(value(person.displayName()))
                    .append(lineSeparator);
            body.append(indent)
                    .append("    Номер счета: ")
                    .append(value(person.accountNumber()))
                    .append(lineSeparator);
            body.append(indent)
                    .append("    Организация: ")
                    .append(value(person.emitentName()))
                    .append(lineSeparator);

            if (person.packageDirectory() != null) {
                body.append(indent)
                        .append("    Черновики ФЭС: ")
                        .append(normalize(person.packageDirectory()))
                        .append(lineSeparator);
            }

            body.append(lineSeparator);
        }

        body.append(indent)
                .append("Автоматическая отправка в Росфинмониторинг не выполнялась.")
                .append(lineSeparator);
        body.append(indent)
                .append("Необходимо проверить подготовленные черновики и принять решение вручную.")
                .append(lineSeparator);
    }

    /** Добавляет путь к XLSX-отчету, когда Zenith его сформировал. */
    private void appendReportFile(StringBuilder body, ZenithProcessingSummary summary, String lineSeparator) {
        if (summary != null && summary.reportFile() != null) {
            body.append("Отчет: ").append(normalize(summary.reportFile())).append(lineSeparator);
        }
    }

    /** Преобразует внутренний код каталога в название для сотрудника. */
    private String displayCatalogName(String catalog) {
        if (catalog == null) {
            return "Неизвестный перечень";
        }

        return switch (catalog.toLowerCase()) {
            case "te2", "te21" -> "Террористы и экстремисты";
            case "mvk" -> "Решения МВК";
            case "un" -> "Перечень ООН";
            case "un-rus" -> "Перечень ООН на русском языке";
            default -> catalog;
        };
    }

    /** Подставляет дефис вместо отсутствующего значения в пользовательском тексте. */
    private String value(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /** Нормализует разделители пути для читаемого текста уведомления. */
    private String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }
}
