# Трехэтапный запуск и уведомления Zenith

Дата: 2026-07-16.

Эта инструкция описывает только необходимую доработку уведомлений для трех серверов. Технические сведения (`idXml`, путь, SHA-256, размер файла) в уведомления не выводятся: они уже сохранены в событиях и журналах.

## Целевая схема

```text
Сервер 1: rfm-downloader
  скачивание реестров
  -> RegistryUpdated

Сервер 2: zenith-processor --drain --mode IMPORT_ONLY
  загрузка реестров в Zenith
  -> ZenithImportCompleted
  -> одно уведомление: «реестры загружены в Zenith»

Сервер 3: zenith-processor --drain --mode CHECK_ONLY
  массовая проверка, XLSX-отчет, анализ, черновики ФЭС
  -> одно уведомление: «результаты проверки Zenith»
```

На сервере 3 должен использоваться именно `CHECK_ONLY`, а не `FULL`: импорт уже был выполнен сервером 2.

## 1. Что остается без изменений

Файл:

```text
common/src/main/java/org/ikozmin/common/notification/ZenithNotificationItem.java
```

Не менять. Его полный актуальный код:

```java
package org.ikozmin.common.notification;

import org.ikozmin.common.event.ZenithProcessingSummary;

/**
 * Результат обработки одного перечня в составе итогового уведомления Zenith.
 */
public record ZenithNotificationItem(
        String catalog,
        ZenithProcessingSummary summary
) {
}
```

Этого достаточно: код перечня нужен для понятного названия, а `summary` хранит результат операции. Технические данные события не копируются в уведомление.

## 2. Обновить текст уведомлений Zenith

Файл:

```text
common/src/main/java/org/ikozmin/common/notification/ZenithNotificationTextBuilder.java
```

Заменить файл полностью:

```java
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
                ? "Реестр загружен в Zenith: " + displayCatalogName(items.getFirst().catalog())
                : "Реестры загружены в Zenith: " + items.size();

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

            body.append(index + 1)
                    .append(". Перечень: ")
                    .append(displayCatalogName(item.catalog()))
                    .append(lineSeparator);

            appendReportFile(body, item.summary(), lineSeparator);
            appendCheckResultBlock(body, "    ", item.summary());
            body.append(lineSeparator);
        }

        body.append("Это автоматическое уведомление. Не надо на него отвечать.")
                .append(lineSeparator);

        return new NotificationMessage(subject, body.toString());
    }

    /**
     * Добавляет результат Zenith в общее уведомление rfm-downloader.
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

    /**
     * Добавляет в текст результат проверки: ошибку, пустой отчет либо найденных лиц.
     */
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
                    .append("Подробности доступны в журнале Zenith.")
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

    /**
     * Добавляет путь к XLSX-отчету, если Zenith его сформировал.
     */
    private void appendReportFile(StringBuilder body, ZenithProcessingSummary summary, String lineSeparator) {
        if (summary != null && summary.reportFile() != null) {
            body.append("Отчет: ").append(normalize(summary.reportFile())).append(lineSeparator);
        }
    }

    /**
     * Преобразует внутренний код каталога в название для сотрудника.
     */
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

    /**
     * Подставляет дефис вместо отсутствующего значения.
     */
    private String value(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /**
     * Нормализует разделители пути для отображения в уведомлении.
     */
    private String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }
}
```

## 3. Изменить результат успешного импорта

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/service/ZenithWorkflowService.java
```

В методе `processImportOnly(...)` найти текущий `return new ZenithProcessingSummary(...)` после публикации `ZenithImportCompletedEvent` и заменить этот фрагмент полностью:

```java
return new ZenithProcessingSummary(
        event.eventId(),
        true,
        null,
        0,
        0,
        null,
        List.of(),
        "Реестр успешно загружен в Zenith."
);
```

Зачем: название перечня уже выводит `ZenithNotificationTextBuilder`, поэтому повторять технический код каталога в сообщении не требуется.

## 4. Изменить накопление результатов в ZenithProcessorMain

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/ZenithProcessorMain.java
```

### 4.1. Учитывать IMPORT_ONLY в итоговом уведомлении

В методе `processRegistryUpdatedEvent(...)` найти:

```java
if (workflowMode != ZenithWorkflowMode.IMPORT_ONLY) {
    notificationItems.add(new ZenithNotificationItem(
            claimedEvent.get().event().catalog(),
            summary
    ));
}
```

Заменить полностью:

```java
notificationItems.add(new ZenithNotificationItem(
        claimedEvent.get().event().catalog(),
        summary
));
```

В блоке `catch` того же метода найти:

```java
if (workflowMode != ZenithWorkflowMode.IMPORT_ONLY) {
    notificationItems.add(new ZenithNotificationItem(
            claimedEvent.get().event().catalog(),
            failureSummary
    ));
}
```

Заменить полностью:

```java
notificationItems.add(new ZenithNotificationItem(
        claimedEvent.get().event().catalog(),
        failureSummary
));
```

Зачем: именно эти два условия сейчас исключают результаты `IMPORT_ONLY` из пакетного уведомления.

### 4.2. Передать режим в отправку уведомления

В `processDrain(...)` в блоке `finally` заменить:

```java
sendNotificationIfNeeded(config, notificationItems);
```

на:

```java
sendNotificationIfNeeded(config, workflowMode, notificationItems);
```

В `processOnce(ZenithConfig config, ZenithWorkflowMode workflowMode)` заменить:

```java
sendNotificationIfNeeded(config, notificationItems);
```

на:

```java
sendNotificationIfNeeded(config, workflowMode, notificationItems);
```

### 4.3. Заменить sendNotificationIfNeeded полностью

```java
/**
 * Отправляет одно итоговое уведомление в соответствии с режимом выполненного workflow.
 */
private void sendNotificationIfNeeded(
        ZenithConfig config,
        ZenithWorkflowMode workflowMode,
        List<ZenithNotificationItem> notificationItems
) {
    if (notificationItems == null || notificationItems.isEmpty()) {
        return;
    }

    if (suppressNotification) {
        log.info("Zenith notification is suppressed by command line option");
        return;
    }

    NotificationDispatcher dispatcher = new NotificationDispatcher(config.getNotifications());

    if (!dispatcher.isEnabled()) {
        return;
    }

    ZenithNotificationTextBuilder textBuilder = new ZenithNotificationTextBuilder();

    NotificationMessage message = workflowMode == ZenithWorkflowMode.IMPORT_ONLY
            ? textBuilder.buildImport(notificationItems)
            : textBuilder.buildCheck(notificationItems);

    dispatcher.send(message);
}
```

`FULL` и `CHECK_ONLY` используют `buildCheck(...)`, а `IMPORT_ONLY` - `buildImport(...)`.

## 5. Конфигурация серверов

### Сервер 1: только скачивание

В рабочем `rfm-downloader/config/config.json` настроить:

```json
"Notifications": {
  "Enabled": false
},
"Events": {
  "Directory": "\\\\server-2\\rfm-events\\registry-updated"
},
"ZenithTrigger": {
  "Enabled": false
}
```

Сервер 1 скачивает реестры и публикует события, но не запускает Zenith и не отправляет уведомления.

### Сервер 2: только импорт в Zenith

В рабочем `zenith-processor/config/zenith-config.json` настроить:

```json
"Events": {
  "RegistryUpdatedDirectory": "\\\\server-2\\rfm-events\\registry-updated",
  "ImportCompletedDirectories": [
    "\\\\server-3\\rfm-events\\zenith-check"
  ],
  "CheckDirectory": "\\\\server-3\\rfm-events\\zenith-check"
},
"Workflow": {
  "Mode": "IMPORT_ONLY"
},
"Notifications": {
  "Enabled": true
}
```

Запуск:

```bat
java -jar zenith-processor.jar --drain --mode IMPORT_ONLY
```

### Сервер 3: только проверка

В рабочем `zenith-processor/config/zenith-config.json` настроить:

```json
"Events": {
  "RegistryUpdatedDirectory": "\\\\server-2\\rfm-events\\registry-updated",
  "ImportCompletedDirectories": [
    "\\\\server-3\\rfm-events\\zenith-check"
  ],
  "CheckDirectory": "\\\\server-3\\rfm-events\\zenith-check"
},
"Workflow": {
  "Mode": "CHECK_ONLY"
},
"Notifications": {
  "Enabled": true
}
```

Запуск:

```bat
java -jar zenith-processor.jar --drain --mode CHECK_ONLY
```

Сервер 3 не импортирует реестр повторно. Он выполняет проверку, выгружает XLSX-отчет, анализирует совпадения, подготавливает черновики ФЭС и отправляет одно итоговое уведомление.

## 6. Проверка

После правок из корня Maven-проекта выполнить:

```bat
mvn clean package
```

Положить события для `te21`, `un` и `mvk` в очередь сервера 2.

Ожидаемый результат этапа 2:

```text
три события -> processed
три события ZenithImportCompleted -> очередь сервера 3
одно уведомление «Реестры загружены в Zenith: 3»
```

После запуска этапа 3 ожидаемо:

```text
три события -> processed
созданы отчеты и черновики ФЭС при наличии новых лиц
одно уведомление «Результаты проверки Zenith: 3 перечня(ей)»
```
