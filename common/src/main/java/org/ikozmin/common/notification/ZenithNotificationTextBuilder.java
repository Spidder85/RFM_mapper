package org.ikozmin.common.notification;

import org.ikozmin.common.event.ZenithProcessingSummary;

import java.nio.file.Path;
import java.util.List;

/**
 * Формирует понятные сотруднику уведомления о загрузке реестров и проверках Zenith.
 */
public final class ZenithNotificationTextBuilder {
    /**
     * Формирует одно итоговое уведомление о загрузке реестров в Zenith.
     */
    public NotificationMessage buildImport(List<ZenithNotificationItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Zenith notification items are empty");
        }

        String lineSeparator = System.lineSeparator();
        String subject = items.size() == 1
                ? "Обновленный реестр Росфинмониторинга загружен в Zenith: " + displayCatalogName(items.getFirst().catalog())
                : "Обновленные реестры Росфинмониторинга загружены в Zenith: " + items.size();

        StringBuilder body = new StringBuilder();
        body.append("Здравствуйте.").append(lineSeparator);
        body.append(lineSeparator);
        body.append("В Zenith завершена загрузка обновленных перечней Росфинмониторинга.")
                .append(lineSeparator);
        body.append("Обработано перечней: ")
                .append(items.size())
                .append(lineSeparator);
        body.append(lineSeparator);

        for (int index = 0; index < items.size(); index++) {
            ZenithNotificationItem item = items.get(index);

            body.append(index + 1)
                    .append(". ")
                    .append(displayCatalogName(item.catalog()))
                    .append(lineSeparator);

            appendImportResultBlock(body, "    ", item.summary());
            body.append(lineSeparator);
        }

        body.append("Это автоматическое уведомление. Не надо на него отвечать.")
                .append(lineSeparator);

        return new NotificationMessage(subject, body.toString());
    }

    /**
     * Формирует одно итоговое уведомление о результатах массовой проверки Zenith.
     */
    public NotificationMessage buildCheck(List<ZenithNotificationItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Zenith notification items are empty");
        }

        String lineSeparator = System.lineSeparator();
        String subject = items.size() == 1
                ? "Результат проверки Zenith: " + displayCatalogName(items.getFirst().catalog())
                : "Результаты проверки Zenith: " + items.size() + " перечня(ей)";

        StringBuilder body = new StringBuilder();
        body.append("Здравствуйте.").append(lineSeparator);
        body.append(lineSeparator);
        body.append("Завершена массовая проверка в Zenith.").append(lineSeparator);
        body.append("Обработано перечней: ").append(items.size()).append(lineSeparator);
        body.append(lineSeparator);

        for (int index = 0; index < items.size(); index++) {
            ZenithNotificationItem item = items.get(index);

            body.append(index+1)
                    .append(". Перечень: ")
                    .append(displayCatalogName(item.catalog()))
                    .append(lineSeparator);

            //appendReportFile(body, item.summary(), lineSeparator);
            appendCheckResultBlock(body, "    ", item.summary());
            body.append(lineSeparator);
        }
        body.append("Это автоматическое уведомление. Не надо на него отвечать.").append(lineSeparator);

        return new NotificationMessage(subject, body.toString());
    }

    /**
     * Добавляет Zenith-блок внутрь общего уведомления RFM.
     */
    public void appendEmbeddedBlock(StringBuilder body, String indent, ZenithProcessingSummary summary) {
        body.append(System.lineSeparator());
        body.append(indent).append("Проверка в Zenith").append(System.lineSeparator());
        appendCheckResultBlock(body, indent + indent, summary);
    }

    /**
     * Добавляет понятный сотруднику итог импорта одного реестра.
     */
    private void appendImportResultBlock(StringBuilder body, String indent, ZenithProcessingSummary summary) {
        String lineSeparator = System.lineSeparator();

        if (summary == null) {
            body.append(indent)
                    .append("Результат загрузки в Zenith недоступен. Проверьте журнал Zenith.")
                    .append(lineSeparator);
            return;
        }

        if (!summary.processed()) {
            body.append(indent)
                    .append("Не удалось загрузить реестр в Zenith.")
                    .append(lineSeparator);
            body.append(indent)
                    .append("Подробности доступны в журнале Zenith.")
                    .append(lineSeparator);
            return;
        }

        body.append(indent)
                .append(value(summary.message()))
                .append(lineSeparator);
    }

    /** Добавляет в текст понятный результат: ошибку, пустой отчет либо найденных лиц. */
    private void appendCheckResultBlock(StringBuilder body, String indent, ZenithProcessingSummary summary) {
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
                    .append(indent)
                    .append("Номер счета: ")
                    .append(value(person.accountNumber()))
                    .append(lineSeparator);
            body.append(indent)
                    .append(indent)
                    .append("Организация: ")
                    .append(value(person.emitentName()))
                    .append(lineSeparator);

            if (person.packageDirectory() != null) {
                body.append(indent)
                        .append(indent)
                        .append("Черновики ФЭС: ")
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
