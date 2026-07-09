package org.ikozmin.zenith.notification;

import org.ikozmin.common.event.ZenithProcessingSummary;
import org.ikozmin.common.notification.NotificationMessage;

import java.nio.file.Path;

public final class ZenithNotificationTextBuilder {
    public NotificationMessage build(String catalog, ZenithProcessingSummary summary) {
        String indent = "    ";

        String subject = "Результат проверки Zenith: " + displayCatalogName(catalog);
        String lineSeparator = System.lineSeparator();
        StringBuilder body = new StringBuilder();

        body.append("Здравствуйте.").append(lineSeparator);
        body.append(lineSeparator);
        body.append("Завершена проверка в Zenith.").append(lineSeparator);
        body.append(lineSeparator);
        body.append("Перечень: ").append(displayCatalogName(catalog)).append(lineSeparator);

        if (summary.reportFile() != null) {
            body.append("Отчет: ").append(normalize(summary.reportFile())).append(lineSeparator);
        }

        body.append(lineSeparator);

        if (summary.newPersons() <= 0) {
            body.append("Новых лиц не найдено.").append(lineSeparator);
            body.append("Всего совпадений в отчете: ").append(summary.totalPersons()).append(lineSeparator);
            body.append(lineSeparator);
            body.append("Это автоматическое уведомление. Не надо на него отвечать.").append(lineSeparator);
            return new NotificationMessage(subject, body.toString());
        }

        body.append("Найдены новые лица: ").append(summary.newPersons()).append(lineSeparator);
        body.append(lineSeparator);

        for (int i = 0; i < summary.persons().size(); i++) {
            ZenithProcessingSummary.Person person = summary.persons().get(i);

            body.append(i + 1).append(". ").append(value(person.displayName())).append(lineSeparator);
            body.append(indent).append("Номер счета: ").append(value(person.accountNumber())).append(lineSeparator);
            body.append(indent).append("Организация: ").append(value(person.emitentName())).append(lineSeparator);

            if (person.packageDirectory() != null) {
                body.append(indent).append("Черновики ФЭС: ")
                        .append(normalize(person.packageDirectory()))
                        .append(lineSeparator);
            }

            body.append(lineSeparator);
        }

        body.append("Автоматическая отправка в Росфинмониторинг не выполнялась.").append(lineSeparator);
        body.append("Необходимо проверить подготовленные черновики и принять решение вручную.").append(lineSeparator);
        body.append(lineSeparator);
        body.append("Это автоматическое уведомление. Не надо на него отвечать.").append(lineSeparator);

        return new NotificationMessage(subject, body.toString());
    }

    private static String displayCatalogName(String catalog) {
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

    private String value(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }
}
