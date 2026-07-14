# Доработка от 2026-07-14: единое уведомление автономного Zenith

## Что уже реализовано

1. RFM формирует одно общее уведомление для нескольких обновленных реестров.
2. При запуске Zenith из RFM отдельное уведомление Zenith подавляется параметром `--suppress-notification`.
3. Для RFM и Zenith используется общий построитель текстового блока `ZenithNotificationTextBuilder`.
4. Пустой XLSX-отчет без листа `Таблица_Проверок` не считается ошибкой.
5. Ошибка одного события не останавливает `--drain`; событие переносится в `failed`.
6. Завершенные events очищаются через 30 дней.
7. Старые TODO от 13.07, помеченные `[x]`, повторно реализовывать не нужно.

## Проблема

При автономном запуске:

```bat
run-zenith-drain.bat --mode CHECK_ONLY
```

`ZenithProcessorMain` получает событие, сразу вызывает `sendNotificationIfNeeded(...)`, затем берет следующее. Поэтому для трех реестров уходят три письма/сообщения.

Нужный результат:

```text
CHECK_ONLY + --drain
  событие te21 ┐
  событие un   ├─> одно итоговое уведомление после разбора очереди
  событие mvk  ┘
```

`--once` продолжает отправлять одно уведомление по одному событию. `--watch` обрабатывает события по мере появления, поэтому отправляет одно уведомление за одну итерацию; это корректно для постоянно работающего процесса.

## 1. Добавить элемент пакетного уведомления

Создать файл:

```text
common/src/main/java/org/ikozmin/common/notification/ZenithNotificationItem.java
```

Код файла полностью:

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

Зачем: класс отделяет накопление результатов запуска от их доставки. `ZenithProcessorMain` больше не должен отправлять сообщение посреди разбора очереди.

## 2. Обновить ZenithNotificationTextBuilder

Файл:

```text
common/src/main/java/org/ikozmin/common/notification/ZenithNotificationTextBuilder.java
```

Добавить импорт:

```java
import java.util.List;
```

Заменить существующий метод `buildStandalone(String catalog, ZenithProcessingSummary summary)` полностью:

```java
/**
 * Собирает отдельное уведомление для одного результата автономного запуска Zenith.
 */
public NotificationMessage buildStandalone(String catalog, ZenithProcessingSummary summary) {
    return buildStandalone(List.of(new ZenithNotificationItem(catalog, summary)));
}
```

После него добавить новый метод полностью:

```java
/**
 * Собирает одно итоговое уведомление по всем перечням, обработанным в запуске Zenith.
 */
public NotificationMessage buildStandalone(List<ZenithNotificationItem> items) {
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
    body.append("Завершена проверка в Zenith.").append(lineSeparator);
    body.append("Обработано перечней: ").append(items.size()).append(lineSeparator);
    body.append(lineSeparator);

    for (int index = 0; index < items.size(); index++) {
        ZenithNotificationItem item = items.get(index);

        body.append(index + 1)
                .append(". Перечень: ")
                .append(displayCatalogName(item.catalog()))
                .append(lineSeparator);

        appendReportFile(body, item.summary(), lineSeparator);
        appendResultBlock(body, "    ", item.summary());
        body.append(lineSeparator);
    }

    body.append("Это автоматическое уведомление. Не надо на него отвечать.")
            .append(lineSeparator);

    return new NotificationMessage(subject, body.toString());
}
```

Остальные методы класса не менять.

## 3. Накопить результаты в ZenithProcessorMain

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/ZenithProcessorMain.java
```

### 3.1. Импорты

Добавить импорты:

```java
import org.ikozmin.common.notification.ZenithNotificationItem;

import java.util.ArrayList;
import java.util.List;
```

### 3.2. Заменить processDrain полностью

```java
/**
 * Обрабатывает всю доступную очередь и отправляет одно уведомление по ее итогам.
 */
private Integer processDrain(ZenithConfig config, ZenithWorkflowMode workflowMode) {
    int processed = 0;
    int failed = 0;
    List<ZenithNotificationItem> notificationItems = new ArrayList<>();

    try {
        while (true) {
            int exitCode = processOnce(config, workflowMode, false, notificationItems);

            if (exitCode == EXIT_NO_EVENTS) {
                log.info("Zenith drain completed. processedEvents={}, failedEvents={}", processed, failed);
                return EXIT_OK;
            }

            if (exitCode == EXIT_EVENT_FAILED) {
                failed++;
                continue;
            }

            if (exitCode != EXIT_OK) {
                return exitCode;
            }

            processed++;
        }
    } finally {
        sendNotificationIfNeeded(config, notificationItems);
    }
}
```

### 3.3. Заменить processOnce с двумя аргументами полностью

```java
/**
 * Обрабатывает одно событие и отправляет уведомление только по результату этой итерации.
 */
private Integer processOnce(ZenithConfig config, ZenithWorkflowMode workflowMode) {
    List<ZenithNotificationItem> notificationItems = new ArrayList<>();
    int exitCode = processOnce(config, workflowMode, requireEvent, notificationItems);
    sendNotificationIfNeeded(config, notificationItems);
    return exitCode;
}
```

### 3.4. Заменить перегруженный processOnce полностью

```java
/**
 * Маршрутизирует одну итерацию в нужную очередь и передает накопитель результатов.
 */
private Integer processOnce(
        ZenithConfig config,
        ZenithWorkflowMode workflowMode,
        boolean requireEventForIteration,
        List<ZenithNotificationItem> notificationItems
) {
    return switch (workflowMode) {
        case FULL -> processRegistryUpdatedEvent(
                config,
                ZenithWorkflowMode.FULL,
                requireEventForIteration,
                notificationItems
        );
        case IMPORT_ONLY -> processRegistryUpdatedEvent(
                config,
                ZenithWorkflowMode.IMPORT_ONLY,
                requireEventForIteration,
                notificationItems
        );
        case CHECK_ONLY -> processImportCompletedEvent(
                config,
                requireEventForIteration,
                notificationItems
        );
    };
}
```

### 3.5. Изменить сигнатуру processRegistryUpdatedEvent

Найти сигнатуру:

```java
private Integer processRegistryUpdatedEvent(
        ZenithConfig config,
        ZenithWorkflowMode workflowMode,
        boolean requireEventForIteration
) {
```

Заменить только сигнатуру на:

```java
private Integer processRegistryUpdatedEvent(
        ZenithConfig config,
        ZenithWorkflowMode workflowMode,
        boolean requireEventForIteration,
        List<ZenithNotificationItem> notificationItems
) {
```

В `try` этого метода заменить блок:

```java
if (workflowMode != ZenithWorkflowMode.IMPORT_ONLY) {
    sendNotificationIfNeeded(config, claimedEvent.get().event().catalog(), summary);
}
```

на:

```java
if (workflowMode != ZenithWorkflowMode.IMPORT_ONLY) {
    notificationItems.add(new ZenithNotificationItem(
            claimedEvent.get().event().catalog(),
            summary
    ));
}
```

В `catch` заменить строку:

```java
saveFailureSummary(config, claimedEvent.get().event().eventId(), e);
```

на полный блок:

```java
ZenithProcessingSummary failureSummary = ZenithProcessingSummary.failed(
        claimedEvent.get().event().eventId(),
        "Ошибка обработки события Zenith: " + e.getMessage()
);
saveSummary(config, failureSummary);

if (workflowMode != ZenithWorkflowMode.IMPORT_ONLY) {
    notificationItems.add(new ZenithNotificationItem(
            claimedEvent.get().event().catalog(),
            failureSummary
    ));
}
```

### 3.6. Изменить сигнатуру processImportCompletedEvent

Найти:

```java
private Integer processImportCompletedEvent(ZenithConfig config, boolean requireEventForIteration) {
```

Заменить на:

```java
private Integer processImportCompletedEvent(
        ZenithConfig config,
        boolean requireEventForIteration,
        List<ZenithNotificationItem> notificationItems
) {
```

В `try` заменить:

```java
sendNotificationIfNeeded(config, claimedEvent.get().event().catalog(), summary);
```

на:

```java
notificationItems.add(new ZenithNotificationItem(
        claimedEvent.get().event().catalog(),
        summary
));
```

В `catch` заменить:

```java
saveFailureSummary(config, claimedEvent.get().event().sourceEventId(), e);
```

на:

```java
ZenithProcessingSummary failureSummary = ZenithProcessingSummary.failed(
        claimedEvent.get().event().sourceEventId(),
        "Ошибка обработки события Zenith: " + e.getMessage()
);
saveSummary(config, failureSummary);
notificationItems.add(new ZenithNotificationItem(
        claimedEvent.get().event().catalog(),
        failureSummary
));
```

### 3.7. Заменить sendNotificationIfNeeded полностью

```java
/**
 * Отправляет одно уведомление по накопленным результатам запуска, если доставка не подавлена.
 */
private void sendNotificationIfNeeded(
        ZenithConfig config,
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

    NotificationMessage message = new ZenithNotificationTextBuilder()
            .buildStandalone(notificationItems);
    dispatcher.send(message);
}
```

Метод `saveFailureSummary(...)` после этой доработки больше не используется. Удалить его целиком, чтобы не оставлять дублирующую логику создания failure summary.

## 4. Комментарии в коде

Классные комментарии добавлены во все production-файлы. Дополнительно нужно документировать каждый нетривиальный метод: файловые операции, HTTP, очереди, бизнес-правила, формирование уведомлений, обработку ошибок и преобразование путей.

Не документируются отдельными Javadoc тривиальные DTO-getter-ы (`getUserName()`, `getPort()`) и record-accessor-ы: их имена и типы исчерпывающе описывают действие. Комментарий вида «возвращает имя пользователя» не добавляет знания и ухудшает код.

## 5. Проверка

Из корня Maven-проекта:

```bat
mvn clean package
```

Положить в `Events.CheckDirectory/new` минимум три корректных события для `te21`, `un`, `mvk` и выполнить:

```bat
run-zenith-drain.bat --mode CHECK_ONLY
```

Ожидаемый результат:

```text
все три события -> processed
создано три XLSX-отчета или три корректных пустых summary
отправлено одно email/Telegram-уведомление
в уведомлении есть три пронумерованных блока перечней
в логе: Zenith drain completed. processedEvents=3, failedEvents=0
```

Затем положить одно некорректное событие между двумя корректными. Ожидаемо: одно попадет в `failed`, корректные будут обработаны, а единое уведомление будет содержать как успешные результаты, так и блок с причиной ошибки.
