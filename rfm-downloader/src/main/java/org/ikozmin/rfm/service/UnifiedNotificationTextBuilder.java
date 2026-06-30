package org.ikozmin.rfm.service;

import org.ikozmin.common.event.ZenithProcessingSummary;
import org.ikozmin.common.notification.NotificationMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class UnifiedNotificationTextBuilder {
    public NotificationMessage build(
            String catalogType,
            String idXml,
            String oldIdXml,
            Path filePath,
            String checksum,
            ZenithProcessingSummary zenithSummary
    ) throws Exception {
        String indent = "    ";
        String subject = "Обновлен перечень Росфинмониторинга: " + displayCatalogName(catalogType);
        StringBuilder body = new StringBuilder();

        body.append("Здравствуйте.").append(System.lineSeparator());
        body.append(System.lineSeparator());
        body.append("В системе Росфинмониторинга опубликована новая версия перечня.").append(System.lineSeparator());
        body.append("Файл успешно загружен и обработан.").append(System.lineSeparator());
        body.append(System.lineSeparator());

        body.append("1. Загрузка перечня").append(System.lineSeparator());
        body.append(indent).append("Перечень: ").append(displayCatalogName(catalogType)).append(System.lineSeparator());
        body.append(indent).append("Дата загрузки: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))).append(System.lineSeparator());
        body.append(indent).append("Предыдущий idXml: ").append(oldIdXml == null ? "отсутствует" : oldIdXml).append(System.lineSeparator());
        body.append(indent).append("Новый idXml: ").append(idXml).append(System.lineSeparator());

        if (filePath != null) {
            body.append(indent).append("Файл для обработки: ").append(filePath.toAbsolutePath()).append(System.lineSeparator());

            if (Files.exists(filePath)) {
                body.append(indent).append("Размер файла: ").append(formatFileSize(Files.size(filePath))).append(System.lineSeparator());
            }
        }

        if (checksum != null && !checksum.isBlank()) {
            body.append(indent).append("SHA-256 архива: ").append(checksum).append(System.lineSeparator());
        }

        body.append(System.lineSeparator());
        body.append("2. Проверка в Zenith").append(System.lineSeparator());

        if (zenithSummary == null) {
            body.append(indent).append("Результат Zenith недоступен. Проверьте журнал zenith-processor.").append(System.lineSeparator());
        } else if (zenithSummary.newPersons() == 0) {
            body.append(indent).append("Новых лиц не найдено.").append(System.lineSeparator());
            body.append(indent).append("Всего совпадений в отчете: ").append(zenithSummary.totalPersons()).append(System.lineSeparator());
        } else {
            body.append(indent).append("Найдены новые лица: ").append(zenithSummary.newPersons()).append(System.lineSeparator());
            body.append(System.lineSeparator());

            int index = 1;
            for (ZenithProcessingSummary.Person person : zenithSummary.persons()) {
                body.append(indent).append(index++).append(". ").append(person.displayName()).append(System.lineSeparator());
                body.append(indent).append(indent).append("Номер счета: ").append(blankToDash(person.accountNumber())).append(System.lineSeparator());
                body.append(indent).append(indent).append("Организация: ").append(blankToDash(person.emitentName())).append(System.lineSeparator());
                body.append(indent).append(indent).append("Черновики ФЭС: ").append(person.packageDirectory()).append(System.lineSeparator());
                body.append(System.lineSeparator());
            }

            body.append("Автоматическая отправка в Росфинмониторинг не выполнялась.").append(System.lineSeparator());
            body.append("Необходимо проверить подготовленные черновики и принять решение вручную.").append(System.lineSeparator());
        }

        body.append(System.lineSeparator());
        body.append("Это автоматическое уведомление. Не надо на него отвечать").append(System.lineSeparator());

        return new NotificationMessage(subject, body.toString());
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " байт";
        if (size < 1024 * 1024) return String.format("%.2f KБайт", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.2f MБайт", size / (1024.0 * 1024));
        return String.format("%.2f ГБайт", size / (1024.0 * 1024 * 1024));
    }

    private String displayCatalogName(String catalogType) {
        if (catalogType == null) {
            return "Неизвестный перечень";
        }

        return switch (catalogType.toLowerCase()) {
            case "te2", "te21" -> "Террористы и экстремисты";
            case "mvk" -> "Решения МВК";
            case "un" -> "Перечень ООН";
            case "un-rus" -> "Перечень ООН на русском языке";
            default -> catalogType;
        };
    }

    private String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
