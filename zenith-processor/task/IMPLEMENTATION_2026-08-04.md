# Корректная обработка ошибки публикации офисного события

## Цель изменения

Не менять порядок запуска, очереди, retry и архитектуру программы. Нужно исправить
только обработку результата после успешного импорта в Zenith:

* ошибка записи события в одну офисную папку не должна отменять результат импорта;
* публикация для остальных офисов должна продолжаться;
* исходное событие обновления реестра должно считаться обработанным, если XML уже
  успешно импортирован в Zenith;
* при полном успехе письмо должно сообщать только о загрузке реестра;
* при частичной доставке письмо должно сообщать, что реестр загружен, перечислять
  офисы, где автоматическая проверка не будет запущена, и предлагать выполнить её
  вручную; путь, stacktrace и текст Java-исключения в письмо не включать.

Повторная доставка, отдельный outbox и изменение расписания в эту задачу не входят.
Недоступный офис не получит событие до следующего штатного обновления реестра; причина
останется в журнале для администратора.

## Почему текущая реализация неверна

`ZenithImportEventPublisher.publish(...)` прерывает цикл при первой ошибке и выбрасывает
`ZenithImportEventPublicationException`. Затем
`ZenithProcessorMain.processRegistryUpdatedEvent(...)` формирует `failed`-summary и
переносит исходное событие в `failed`. В логе 03.08.2026 это привело к счётчику
`processedEvents=0, failedEvents=1`, хотя импорт XML в Zenith и публикация для трёх
офисов уже завершились успешно.

Ошибка доступа к папке НЧ -- это ошибка публикации одного вторичного события, а не
ошибка импорта перечня в Zenith.

## 1. Результат публикации

**Создать файл:**
`zenith-processor/src/main/java/org/ikozmin/zenith/event/ZenithImportPublicationResult.java`.

Этот объект передаёт в workflow полный результат попытки публикации: куда событие
создано и для каких офисов создать его не удалось.

```java
package org.ikozmin.zenith.event;

import java.util.List;

/** Результат публикации события о завершённом импорте по офисным очередям. */
public record ZenithImportPublicationResult(
        int destinationCount,
        List<String> publishedDestinationNames,
        List<String> failedDestinationNames
) {
    public ZenithImportPublicationResult {
        publishedDestinationNames = List.copyOf(publishedDestinationNames);
        failedDestinationNames = List.copyOf(failedDestinationNames);
    }

    /** Возвращает true, если хотя бы в одну офисную папку записать событие не удалось. */
    public boolean hasFailures() {
        return !failedDestinationNames.isEmpty();
    }
}
```

## 2. Понятные имена офисов в конфигурации

Текущий список `ImportCompletedDirectories` содержит только технические пути. Чтобы в
уведомлении было написано «Офис НЧ», а не UNC-путь, заменить этот список объектами с
понятным именем офиса и его техническим путём.

**Файлы:**

* `zenith-processor/config/zenith-config.template.json`;
* рабочий `config/zenith-config.json` экземпляра с режимом `IMPORT_ONLY` или `FULL`.

Заменить только блок `Events`:

```json
"Events": {
  "RegistryUpdatedDirectory": "events/registry-updated",
  "ImportCompletedDestinations": [
    {
      "Name": "Центральный сервер проверки",
      "Directory": "events/zenith-imported/client-main"
    },
    {
      "Name": "Офис АЛМ",
      "Directory": "\\\\P-LKM\\zenit\\z-finmon\\alm\\events"
    },
    {
      "Name": "Офис ИЖ",
      "Directory": "\\\\P-LKM\\zenit\\z-finmon\\izh\\events"
    },
    {
      "Name": "Офис НЧ",
      "Directory": "\\\\P-LKM\\zenit\\z-finmon\\nch\\events"
    }
  ],
  "CheckDirectory": "events/zenith-imported/client-main"
}
```

`ImportCompletedDirectories` удалить. В конфигурации сервера, работающего только в
`CHECK_ONLY`, блок `ImportCompletedDestinations` не обязателен, если `CheckDirectory`
уже задан явно.

## 3. Модель конфигурации

**Файл:** `zenith-processor/src/main/java/org/ikozmin/zenith/config/ZenithConfig.java`.

В классе `Events` удалить поле:

```java
@JsonProperty("ImportCompletedDirectories")
private List<String> importCompletedDirectories;
```

Удалить метод `getImportCompletedDirectories()`. Вместо него добавить следующие поля,
методы и record внутрь класса `Events`:

```java
@JsonProperty("ImportCompletedDestinations")
private List<ImportCompletedDestination> importCompletedDestinations;

@JsonProperty("CheckDirectory")
private String checkDirectory;

public List<ImportCompletedDestination> getImportCompletedDestinations() {
    if (importCompletedDestinations == null || importCompletedDestinations.isEmpty()) {
        return List.of(new ImportCompletedDestination(
                "Локальный сервер проверки",
                "events/zenith-imported"
        ));
    }

    return importCompletedDestinations;
}

public String getCheckDirectory() {
    if (checkDirectory != null && !checkDirectory.isBlank()) {
        return checkDirectory;
    }

    return getImportCompletedDestinations().getFirst().directory();
}

@JsonIgnoreProperties(ignoreUnknown = true)
public record ImportCompletedDestination(
        @JsonProperty("Name") String name,
        @JsonProperty("Directory") String directory
) {
    public ImportCompletedDestination {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Import destination name is blank");
        }
        if (directory == null || directory.isBlank()) {
            throw new IllegalArgumentException("Import destination directory is blank");
        }
    }
}
```

Остальные поля `Events` и метод `getRegistryUpdatedDirectory()` не менять.

## 4. Публикация во все офисы без прерывания цикла

**Файл:**
`zenith-processor/src/main/java/org/ikozmin/zenith/event/ZenithImportEventPublisher.java`.

Заменить класс полностью. Изменение ловит ошибку отдельно для каждого адресата,
записывает её в лог и продолжает публикацию в следующие папки.

```java
package org.ikozmin.zenith.event;

import org.ikozmin.common.event.FileEventPublisher;
import org.ikozmin.common.event.RegistryUpdatedEvent;
import org.ikozmin.common.event.ZenithImportCompletedEvent;
import org.ikozmin.zenith.config.ZenithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Публикует офисные события после успешного центрального импорта реестра. */
public final class ZenithImportEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(ZenithImportEventPublisher.class);

    private final ZenithConfig.Events eventsConfig;

    public ZenithImportEventPublisher(ZenithConfig.Events eventsConfig) {
        this.eventsConfig = eventsConfig;
    }

    /**
     * Публикует событие во все настроенные офисные очереди.
     * Ошибка одного адресата не отменяет публикацию для остальных адресатов.
     */
    public ZenithImportPublicationResult publish(RegistryUpdatedEvent sourceEvent) {
        ZenithImportCompletedEvent event = new ZenithImportCompletedEvent(
                sourceEvent.eventId() + "-imported",
                ZenithImportCompletedEvent.TYPE,
                LocalDateTime.now(),
                sourceEvent.eventId(),
                sourceEvent.catalog(),
                sourceEvent.idXml(),
                sourceEvent.registryFile(),
                LocalDateTime.now().toString()
        );

        List<String> publishedDestinations = new ArrayList<>();
        List<String> failedDestinations = new ArrayList<>();
        List<ZenithConfig.Events.ImportCompletedDestination> destinations =
                eventsConfig.getImportCompletedDestinations();

        for (ZenithConfig.Events.ImportCompletedDestination destination : destinations) {
            try {
                Path file = new FileEventPublisher(
                        Path.of(destination.directory()).resolve("new")
                ).publish(event);

                publishedDestinations.add(destination.name());

                log.info(
                        "Zenith import completed event published. catalog={}, destination={}, file={}",
                        event.catalog(),
                        destination.name(),
                        file.toAbsolutePath()
                );
            } catch (RuntimeException e) {
                failedDestinations.add(destination.name());

                log.warn(
                        "Zenith import completed event was not published. catalog={}, destination={}, directory={}, error={}",
                        event.catalog(),
                        destination.name(),
                        destination.directory(),
                        e.getMessage(),
                        e
                );
            }
        }

        return new ZenithImportPublicationResult(
                destinations.size(),
                publishedDestinations,
                failedDestinations
        );
    }
}
```

После замены удалить файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/event/ZenithImportEventPublicationException.java
```

Он больше не нужен: ошибка публикации не является исключением workflow.

## 5. Формирование честного результата импорта

**Файл:**
`zenith-processor/src/main/java/org/ikozmin/zenith/service/ZenithWorkflowService.java`.

В методе `processImportOnly(...)` заменить строку:

```java
new ZenithImportEventPublisher(config.getEvents()).publish(event);
```

и существующий `return new ZenithProcessingSummary(...)` на этот полный фрагмент:

```java
ZenithImportPublicationResult publicationResult =
        new ZenithImportEventPublisher(config.getEvents()).publish(event);

return new ZenithProcessingSummary(
        event.eventId(),
        true,
        null,
        0,
        0,
        null,
        List.of(),
        buildImportMessage(publicationResult)
);
```

В конец класса, перед последней `}`, добавить метод:

```java
/** Формирует понятный сотруднику итог импорта и публикации офисных событий. */
private String buildImportMessage(ZenithImportPublicationResult publicationResult) {
    if (!publicationResult.hasFailures()) {
        return "Реестр успешно загружен в Zenith.";
    }

    StringBuilder message = new StringBuilder();
    message.append("Реестр успешно загружен в Zenith.")
            .append(System.lineSeparator())
            .append(System.lineSeparator())
            .append("Не удалось передать служебное событие для автоматического запуска ")
            .append("проверки в ")
            .append(publicationResult.failedDestinationNames().size())
            .append(" из ")
            .append(publicationResult.destinationCount())
            .append(" офисов:")
            .append(System.lineSeparator());

    for (int index = 0; index < publicationResult.failedDestinationNames().size(); index++) {
        message.append(index + 1)
                .append(") ")
                .append(publicationResult.failedDestinationNames().get(index))
                .append(System.lineSeparator());
    }

    message.append(System.lineSeparator())
            .append(publicationResult.failedDestinationNames().size() == 1
                    ? "В указанном офисе необходимо выполнить проверку вручную."
                    : "В указанных офисах необходимо выполнить проверку вручную.")
            .append(System.lineSeparator())
            .append("Техническая информация зарегистрирована в журнале программы.");

    return message.toString();
}
```

Добавить импорт в начало файла:

```java
import org.ikozmin.zenith.event.ZenithImportPublicationResult;
```

В `processFull(...)` заменить единственную строку публикации:

```java
new ZenithImportEventPublisher(config.getEvents()).publish(event);
```

на:

```java
ZenithImportPublicationResult publicationResult =
        new ZenithImportEventPublisher(config.getEvents()).publish(event);
```

После вызова `createReportIfEnabled(...)` и до `return summary;` заменить возврат на:

```java
return new ZenithProcessingSummary(
        summary.eventId(),
        summary.processed(),
        summary.reportFile(),
        summary.totalPersons(),
        summary.newPersons(),
        summary.fesPackageRoot(),
        summary.persons(),
        summary.message() + System.lineSeparator() + buildImportMessage(publicationResult)
);
```

Так локальная проверка FULL не будет отменена из-за недоступной сетевой папки.

## 6. Очередь и retention

**Файл:**
`zenith-processor/src/main/java/org/ikozmin/zenith/ZenithProcessorMain.java`.

Удалить импорт:

```java
import org.ikozmin.zenith.event.ZenithImportEventPublicationException;
```

Удалить полностью методы:

```java
private ZenithProcessingSummary createRegistryFailureSummary(...)
private ZenithImportEventPublicationException findImportEventPublicationFailure(...)
```

В блоке `catch` метода `processRegistryUpdatedEvent(...)` заменить создание summary:

```java
ZenithProcessingSummary failureSummary = createRegistryFailureSummary(
        claimedEvent.get().event().eventId(),
        retryable,
        e,
        workflowMode
);
```

на:

```java
ZenithProcessingSummary failureSummary = createFailureSummary(
        claimedEvent.get().event().eventId(),
        retryable
);
```

Другой код `catch` не менять. После изменения сюда будут попадать только реальные
ошибки импорта/проверки Zenith, а не частичный результат публикации событий.

В `applyEventRetention(...)` заменить цикл:

```java
for (String directory : config.getEvents().getImportCompletedDirectories()) {
    retentionService.apply(Path.of(directory));
}
```

на:

```java
for (ZenithConfig.Events.ImportCompletedDestination destination
        : config.getEvents().getImportCompletedDestinations()) {
    retentionService.apply(Path.of(destination.directory()));
}
```

## 7. Уведомления

`ZenithNotificationTextBuilder` менять не нужно. Для `IMPORT_ONLY` он уже выводит
`summary.message()`. После изменения workflow письмо при частичной доставке будет
выглядеть так:

```text
Реестр успешно загружен в Zenith.

Не удалось передать служебное событие для автоматического запуска проверки в 1 из 4 офисов:
1) Офис НЧ

В указанном офисе необходимо выполнить проверку вручную.
Техническая информация зарегистрирована в журнале программы.
```

При полной доставке пользователь увидит только: `Реестр успешно загружен в Zenith.`
Сообщение о частичной доставке не утверждает, что импорт не состоялся, не содержит
технических путей и не обещает повторной отправки, которой в текущем сценарии нет.

## 8. Проверка

1. Временно запретить запись только в папку НЧ.
2. Поместить один `RegistryUpdatedEvent` в центральную очередь и запустить
   `IMPORT_ONLY --drain`.
3. Проверить журнал: импорт XML в Zenith успешен; созданы JSON для центрального
   сервера, АЛМ и ИЖ; для НЧ есть `WARN`, но нет `ERROR Zenith registry event failed`.
4. Проверить очередь исходного события: оно перенесено в `processed`, не в `failed` и
   не в `retry`.
5. Проверить письмо: в нём сказано об успешной загрузке и о недоставке события в НЧ.
6. Проверить локальный `CHECK_ONLY`: он продолжает штатно обрабатывать созданное
   локальное событие.
7. Отдельно вызвать реальную ошибку API Zenith до завершения импорта. В этом случае
   исходное событие должно сохранить прежнее поведение `retry` или `failed`.
