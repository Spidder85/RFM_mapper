# Исправление частичной ошибки публикации офисных событий

## Согласованная цель

Изменяется только обработка результата публикации служебных событий после успешной
загрузки реестра в Zenith.

Не меняются:

* порядок запуска программ и режимы `IMPORT_ONLY`, `FULL`, `CHECK_ONLY`;
* очередь событий, retry, retention и архивирование;
* логика импорта XML в Zenith;
* запуск проверки в офисах, куда служебное событие уже успешно передано.

Требуемое поведение:

1. Если реестр загружен в Zenith и события созданы во всех офисных папках, результат
   успешный, текст уведомления: `Реестр успешно загружен в Zenith.`
2. Если реестр загружен в Zenith, но запись в одну или несколько офисных папок не
   удалась, импорт всё равно успешный. Исходное событие переносится в `processed`.
3. Ошибка одной папки не останавливает попытки записи в остальные папки.
4. Уведомление перечисляет понятные названия недоступных офисов и сообщает, что в них
   проверку надо выполнить вручную.
5. Полный технический текст ошибки и UNC-путь остаются только в логе.

Никакой повторной доставки в этой доработке нет: если папка офиса недоступна, её
клиент `CHECK_ONLY` не получит событие текущего реестра. Это явно сообщается в письме,
и проверка в таком офисе запускается вручную.

## Причина текущей ошибки

Сейчас `ZenithImportEventPublisher.publish(...)` при первой ошибке записи выбрасывает
`ZenithImportEventPublicationException`. Это исключение попадает в
`ZenithProcessorMain.processRegistryUpdatedEvent(...)`, после чего всё исходное
`RegistryUpdatedEvent` считается ошибочным.

В логе от 03.08.2026 XML был успешно импортирован в Zenith, а события были созданы для
центрального сервера, АЛМ и ИЖ. Ошибка `AccessDeniedException` была только при создании
папки НЧ. Несмотря на это, программа записала `processedEvents=0, failedEvents=1`.
Такой итог неверен: сбой доставки технического события не является сбоем импорта
реестра.

## 1. Добавить имена офисов в конфигурацию

Понятные названия нужны только для пользовательского уведомления. Технический путь
остаётся в том же конфиге и используется для записи JSON-события.

**Файлы, где заменить блок `Events` полностью:**

* `zenith-processor/config/zenith-config.template.json`;
* рабочий `config/zenith-config.json` центрального экземпляра Zenith, работающего в
  режиме `IMPORT_ONLY` или `FULL`.

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

Из этих двух файлов удалить старое свойство `ImportCompletedDirectories`.

Конфигурации офисных экземпляров, которые запускаются только в `CHECK_ONLY`, менять
не требуется: они используют свой `CheckDirectory` и не публикуют события другим
офисам.

## 2. Изменить модель `Events`

**Файл:**
`zenith-processor/src/main/java/org/ikozmin/zenith/config/ZenithConfig.java`.

В nested-классе `Events` удалить старое поле и старый getter:

```java
@JsonProperty("ImportCompletedDirectories")
private List<String> importCompletedDirectories;

public List<String> getImportCompletedDirectories() {
    if (importCompletedDirectories == null || importCompletedDirectories.isEmpty()) {
        return List.of("events/zenith-imported");
    }

    return importCompletedDirectories;
}
```

Заменить их на следующий полный фрагмент. Поместить его в `Events` рядом с остальными
полями и методами:

```java
@JsonProperty("ImportCompletedDestinations")
private List<ImportCompletedDestination> importCompletedDestinations;

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

Старый `getCheckDirectory()` удалить, так как в приведённом фрагменте находится его
новая полная версия. Поле `checkDirectory` оставить в классе: оно уже существует и
нужно клиентам `CHECK_ONLY`.

## 3. Добавить объект результата публикации

**Создать файл:**
`zenith-processor/src/main/java/org/ikozmin/zenith/event/ZenithImportPublicationResult.java`.

```java
package org.ikozmin.zenith.event;

import java.util.List;

/** Хранит итог публикации служебного события по офисным очередям. */
public record ZenithImportPublicationResult(
        int destinationCount,
        List<String> failedDestinationNames
) {
    public ZenithImportPublicationResult {
        failedDestinationNames = List.copyOf(failedDestinationNames);
    }

    /** Возвращает true, когда событие не удалось записать хотя бы в одну папку офиса. */
    public boolean hasFailures() {
        return !failedDestinationNames.isEmpty();
    }
}
```

Список успешно обработанных офисов здесь не нужен: для уведомления важны только общее
количество адресатов и названия офисов, в которых требуется ручной запуск проверки.

## 4. Публиковать событие во все офисы

**Файл, который заменить полностью:**
`zenith-processor/src/main/java/org/ikozmin/zenith/event/ZenithImportEventPublisher.java`.

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
     * Публикует событие в каждую офисную очередь.
     * Ошибка одного офиса фиксируется в журнале, но не отменяет другие публикации.
     */
    public ZenithImportPublicationResult publish(RegistryUpdatedEvent sourceEvent) {
        LocalDateTime createdAt = LocalDateTime.now();
        ZenithImportCompletedEvent event = new ZenithImportCompletedEvent(
                sourceEvent.eventId() + "-imported",
                ZenithImportCompletedEvent.TYPE,
                createdAt,
                sourceEvent.eventId(),
                sourceEvent.catalog(),
                sourceEvent.idXml(),
                sourceEvent.registryFile(),
                createdAt.toString()
        );

        List<ZenithConfig.Events.ImportCompletedDestination> destinations =
                eventsConfig.getImportCompletedDestinations();
        List<String> failedDestinationNames = new ArrayList<>();

        for (ZenithConfig.Events.ImportCompletedDestination destination : destinations) {
            try {
                Path file = new FileEventPublisher(
                        Path.of(destination.directory()).resolve("new")
                ).publish(event);

                log.info(
                        "Zenith import completed event published. catalog={}, destination={}, file={}",
                        event.catalog(),
                        destination.name(),
                        file.toAbsolutePath()
                );
            } catch (RuntimeException e) {
                failedDestinationNames.add(destination.name());

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
                failedDestinationNames
        );
    }
}
```

**Удалить файл:**

```text
zenith-processor/src/main/java/org/ikozmin/zenith/event/ZenithImportEventPublicationException.java
```

Он больше не нужен: частичная ошибка публикации не должна быть исключением workflow.

## 5. Сформировать результат для уведомления

**Файл:**
`zenith-processor/src/main/java/org/ikozmin/zenith/service/ZenithWorkflowService.java`.

### 5.1. Добавить import

Добавить к существующим import:

```java
import org.ikozmin.zenith.event.ZenithImportPublicationResult;
```

### 5.2. Изменить `processImportOnly(...)`

После существующей проверки `if (!imported) { ... }` удалить строку:

```java
new ZenithImportEventPublisher(config.getEvents()).publish(event);
```

и заменить существующий `return new ZenithProcessingSummary(...)` следующим полным
фрагментом:

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

### 5.3. Изменить `processFull(...)`

Заменить строку:

```java
new ZenithImportEventPublisher(config.getEvents()).publish(event);
```

на:

```java
ZenithImportPublicationResult publicationResult =
        new ZenithImportEventPublisher(config.getEvents()).publish(event);
```

Найти в конце метода текущий фрагмент:

```java
log.info("Full Zenith workflow completed. eventId={}", event.eventId());

return summary;
```

и заменить его полностью на:

```java
log.info("Full Zenith workflow completed. eventId={}", event.eventId());

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

### 5.4. Добавить метод `buildImportMessage(...)`

В конец класса, перед последней `}`, добавить метод полностью:

```java
/** Формирует пользовательский итог загрузки реестра и публикации офисных событий. */
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

## 6. Убрать устаревшую обработку исключения

**Файл:**
`zenith-processor/src/main/java/org/ikozmin/zenith/ZenithProcessorMain.java`.

### 6.1. Удалить import

```java
import org.ikozmin.zenith.event.ZenithImportEventPublicationException;
```

### 6.2. Упростить обработчик ошибок

В `processRegistryUpdatedEvent(...)` в блоке `catch (Exception e)` заменить только
создание `failureSummary`:

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

Затем удалить методы полностью:

```java
private ZenithProcessingSummary createRegistryFailureSummary(...)
private ZenithImportEventPublicationException findImportEventPublicationFailure(...)
```

Остальной блок `catch` не менять. Теперь в него попадают только фактические ошибки
импорта или проверки Zenith. Частичная ошибка записи офисного события обработана внутри
publisher и до `catch` не доходит.

### 6.3. Сохранить retention для офисных очередей

В `applyEventRetention(...)` заменить старый цикл:

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

Это не меняет retention: он продолжит обрабатывать те же офисные папки, но путь теперь
берётся из объекта `Name + Directory`.

## 7. Уведомления

`ZenithNotificationTextBuilder` менять не нужно. Для режима `IMPORT_ONLY` он уже
выводит `summary.message()`.

При полном успехе пользователь получит только:

```text
Реестр успешно загружен в Zenith.
```

При недоступности НЧ из четырёх настроенных офисов:

```text
Реестр успешно загружен в Zenith.

Не удалось передать служебное событие для автоматического запуска проверки в 1 из 4 офисов:
1) Офис НЧ

В указанном офисе необходимо выполнить проверку вручную.
Техническая информация зарегистрирована в журнале программы.
```

## 8. Проверка результата

1. Временно запретить центральному экземпляру запись только в папку НЧ.
2. Поместить одно новое событие `RegistryUpdatedEvent` в очередь и выполнить
   `IMPORT_ONLY --drain`.
3. Проверить журнал:
   * импорт XML в Zenith завершён успешно;
   * события созданы для доступных офисов;
   * по НЧ есть `WARN` с технической причиной;
   * нет сообщения `Zenith registry event failed`.
4. Проверить очередь: исходный `RegistryUpdatedEvent` находится в `processed`, а не в
   `retry` или `failed`.
5. Проверить письмо: текст соответствует примеру из раздела 7, без UNC-пути и
   stacktrace.
6. Убедиться, что клиентские `CHECK_ONLY` для доступных офисов выполняют проверку как
   раньше.
7. Отдельно проверить реальный сбой API Zenith до окончания импорта. Тогда событие
   должно по-прежнему попадать в `retry` или `failed`: это настоящее исключение
   workflow, которое данная доработка не маскирует.
