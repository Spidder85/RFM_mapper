package org.ikozmin.rfm.service;

import org.ikozmin.common.event.ZenithProcessingSummary;
import org.ikozmin.common.notification.NotificationMessage;
import org.ikozmin.rfm.model.CatalogType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class UnifiedNotificationTextBuilder {
    public NotificationMessage build(
            List<RegistryNotificationItem> items
    ) throws Exception {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Notification items are empty");
        }

        String subject = items.size() == 1
                ? "Обновлен перечень Росфинмониторинга: " + displayCatalogName(items.getFirst().result().catalogType().getCode())
                : "Обновлены перечни Росфинмониторинга: " + items.size();

        String indent = "    ";
        StringBuilder body = new StringBuilder();

        body.append("Здравствуйте.").append(System.lineSeparator());
        body.append(System.lineSeparator());
        body.append("В системе Росфинмониторинга опубликованы обновления перечней.").append(System.lineSeparator());
        body.append("Файлы успешно загружены.").append(System.lineSeparator());
        body.append(System.lineSeparator());
        body.append("Дата проверки: ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")))
                .append(System.lineSeparator());
        body.append(System.lineSeparator());

        for (RegistryNotificationItem item : items) {
            UpdateResult result = item.result();

            body.append(indent).append("Предыдущий idXml: ")
                    .append(result.oldIdXml() == null ? "отсутствует" : result.oldIdXml())
                    .append(System.lineSeparator());

            body.append(indent).append("Новый idXml: ")
                    .append(result.idXml())
                    .append(System.lineSeparator());

            if (result.file() != null) {
                body.append(indent).append("Файл для обработки: ")
                        .append(result.file().toAbsolutePath())
                        .append(System.lineSeparator());

                if (Files.exists(result.file())) {
                    body.append(indent).append("Размер файла: ")
                            .append(formatFileSize(Files.size(result.file())))
                            .append(System.lineSeparator());
                }
            }

            if (result.sha256() != null && !result.sha256().isBlank()) {
                body.append(indent).append("SHA-256 архива: ")
                        .append(result.sha256())
                        .append(System.lineSeparator());
            }

            appendZenithBlock(body, indent, item.zenithSummary());
            body.append(System.lineSeparator());
        }
        body.append("Это автоматическое уведомление. Не надо на него отвечать.").append(System.lineSeparator());

        return new NotificationMessage(subject, body.toString());
    }

    public NotificationMessage build(
            String catalogType,
            String idXml,
            String oldIdXml,
            Path filePath,
            String checksum,
            ZenithProcessingSummary zenithSummary
    ) throws Exception {
        UpdateResult result = new UpdateResult(
                true,
                CatalogType.from(catalogType),
                oldIdXml,
                idXml,
                filePath,
                filePath,
                checksum,
                safeFileSize(filePath),
                LocalDateTime.now().toString()
        );

        return build(List.of(new RegistryNotificationItem(result, zenithSummary)));
    }

    private void appendZenithBlock(StringBuilder body, String indent, ZenithProcessingSummary zenithSummary) {
        body.append(System.lineSeparator());
        body.append(indent).append("Проверка в Zenith").append(System.lineSeparator());

        if (zenithSummary == null) {
            body.append(indent).append(indent)
                    .append("Результат Zenith недоступен. Проверьте журнал zenith-processor и каталог events.")
                    .append(System.lineSeparator());
            return;
        }

        if (zenithSummary.newPersons() == 0) {
            body.append(indent).append(indent)
                    .append("Новых лиц не найдено.")
                    .append(System.lineSeparator());
            body.append(indent).append(indent)
                    .append("Всего совпадений в отчете: ")
                    .append(zenithSummary.totalPersons())
                    .append(System.lineSeparator());
            return;
        }

        body.append(indent).append(indent)
                .append("Найдены новые лица: ")
                .append(zenithSummary.newPersons())
                .append(System.lineSeparator());

        int index = 1;
        for (ZenithProcessingSummary.Person person : zenithSummary.persons()) {
            body.append(indent).append(indent)
                    .append(index++)
                    .append(". ")
                    .append(blankToDash(person.displayName()))
                    .append(System.lineSeparator());
            body.append(indent).append(indent).append(indent)
                    .append("Номер счета: ")
                    .append(blankToDash(person.accountNumber()))
                    .append(System.lineSeparator());
            body.append(indent).append(indent).append(indent)
                    .append("Организация: ")
                    .append(blankToDash(person.emitentName()))
                    .append(System.lineSeparator());
            body.append(indent).append(indent).append(indent)
                    .append("Черновики ФЭС: ")
                    .append(person.packageDirectory() == null ? "-" : person.packageDirectory())
                    .append(System.lineSeparator());
        }
        body.append(System.lineSeparator());

        body.append(indent).append(indent)
                .append("Автоматическая отправка в Росфинмониторинг не выполнялась.")
                .append(System.lineSeparator());
        body.append(indent).append(indent)
                .append("Необходимо проверить подготовленные черновики и принять решение вручную.")
                .append(System.lineSeparator());
    }

    private long safeFileSize(Path file) {
        if (file == null || !Files.exists(file)) {
            return 0L;
        }

        try {
            return Files.size(file);
        } catch (Exception e) {
            return 0L;
        }
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " байт";
        if (size < 1024 * 1024) return String.format("%.2f KБ", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.2f MБ", size / (1024.0 * 1024));
        return String.format("%.2f ГБ", size / (1024.0 * 1024 * 1024));
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
