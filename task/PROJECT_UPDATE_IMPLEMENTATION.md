# Инструкция по доработкам от 2026-07-09

Корень проекта:

```text
G:\tmp\fedfsm\java
```

Исходный код в этой инструкции автоматически не менялся. Ниже описано, что именно изменить вручную.

Текущая рабочая логика:

1. `rfm-downloader` скачивает обновленные реестры и публикует события `RegistryUpdated`.
2. `zenith-processor` в режиме `FULL` импортирует реестр, публикует офисные события `ZenithImportCompleted`, запускает массовую проверку, выгружает отчет и сохраняет summary.
3. Офисный `zenith-processor` в режиме `CHECK_ONLY` берет события из своей сетевой папки, запускает проверку, выгружает отчет и при необходимости отправляет уведомление.

Новые доработки нужны для трех вещей:

1. Убрать дублирование текста Zenith-уведомлений.
2. Исключить двойную отправку уведомлений, когда включены уведомления и в RFM, и в Zenith.
3. Сделать запуск Zenith из RFM пакетным: обработать все события за запуск, а не только первое.

---

## 1. Общий builder для текста Zenith

### Зачем

Сейчас Zenith-блок уведомления собирается в двух местах:

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/service/UnifiedNotificationTextBuilder.java
zenith-processor/src/main/java/org/ikozmin/zenith/notification/ZenithNotificationTextBuilder.java
```

Это плохо: при изменении текста легко поправить один модуль и забыть второй.

Правильнее вынести форматирование результата Zenith в `common`, а `rfm-downloader` и `zenith-processor` должны только вызывать его.

---

### 1.1. Создать общий builder

Создать файл:

```text
common/src/main/java/org/ikozmin/common/notification/ZenithNotificationTextBuilder.java
```

Полный код файла:

```java
package org.ikozmin.common.notification;

import org.ikozmin.common.event.ZenithProcessingSummary;

import java.nio.file.Path;

public final class ZenithNotificationTextBuilder {
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

    public void appendEmbeddedBlock(StringBuilder body, String indent, ZenithProcessingSummary summary) {
        body.append(System.lineSeparator());
        body.append(indent).append("Проверка в Zenith").append(System.lineSeparator());
        appendResultBlock(body, indent + indent, summary);
    }

    private void appendResultBlock(StringBuilder body, String indent, ZenithProcessingSummary summary) {
        String lineSeparator = System.lineSeparator();

        if (summary == null) {
            body.append(indent)
                    .append("Результат Zenith недоступен. Проверьте журнал zenith-processor и каталог events.")
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

    private void appendReportFile(StringBuilder body, ZenithProcessingSummary summary, String lineSeparator) {
        if (summary != null && summary.reportFile() != null) {
            body.append("Отчет: ").append(normalize(summary.reportFile())).append(lineSeparator);
        }
    }

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

    private String value(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }
}
```

---

### 1.2. Обновить RFM builder

Файл:

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/service/UnifiedNotificationTextBuilder.java
```

Заменить файл полностью:

```java
package org.ikozmin.rfm.service;

import org.ikozmin.common.event.ZenithProcessingSummary;
import org.ikozmin.common.notification.NotificationMessage;
import org.ikozmin.common.notification.ZenithNotificationTextBuilder;
import org.ikozmin.rfm.model.CatalogType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class UnifiedNotificationTextBuilder {
    private final ZenithNotificationTextBuilder zenithTextBuilder = new ZenithNotificationTextBuilder();

    public NotificationMessage build(List<RegistryNotificationItem> items) throws Exception {
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

        for (int i = 0; i < items.size(); i++) {
            RegistryNotificationItem item = items.get(i);
            UpdateResult result = item.result();

            body.append(i + 1)
                    .append(". ")
                    .append(displayCatalogName(result.catalogType().getCode()))
                    .append(System.lineSeparator());

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

            zenithTextBuilder.appendEmbeddedBlock(body, indent, item.zenithSummary());
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
        if (size < 1024) {
            return size + " байт";
        }

        if (size < 1024 * 1024) {
            return String.format("%.2f KБ", size / 1024.0);
        }

        if (size < 1024 * 1024 * 1024) {
            return String.format("%.2f MБ", size / (1024.0 * 1024));
        }

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
}
```

Что изменилось:

1. Zenith-блок больше не формируется вручную внутри RFM.
2. RFM вызывает общий `ZenithNotificationTextBuilder`.
3. Удаляется дублирование текста между RFM и Zenith.

---

### 1.3. Обновить ZenithProcessorMain

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/ZenithProcessorMain.java
```

Удалить импорт:

```java
import org.ikozmin.zenith.notification.ZenithNotificationTextBuilder;
```

Добавить импорт:

```java
import org.ikozmin.common.notification.ZenithNotificationTextBuilder;
```

Найти метод:

```java
private void sendNotificationIfNeeded(ZenithConfig config, String catalog, ZenithProcessingSummary summary) {
    NotificationDispatcher dispatcher = new NotificationDispatcher(config.getNotifications());

    if (!dispatcher.isEnabled()) {
        return;
    }

    NotificationMessage message = new ZenithNotificationTextBuilder().build(catalog, summary);
    dispatcher.send(message);
}
```

Заменить на:

```java
private void sendNotificationIfNeeded(ZenithConfig config, String catalog, ZenithProcessingSummary summary) {
    if (suppressNotification) {
        log.info("Zenith notification is suppressed by command line option");
        return;
    }

    NotificationDispatcher dispatcher = new NotificationDispatcher(config.getNotifications());

    if (!dispatcher.isEnabled()) {
        return;
    }

    NotificationMessage message = new ZenithNotificationTextBuilder().buildStandalone(catalog, summary);
    dispatcher.send(message);
}
```

---

### 1.4. Удалить старый Zenith builder

Удалить файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/notification/ZenithNotificationTextBuilder.java
```

Папку можно оставить, если в ней есть другие файлы. Если папка стала пустой, ее тоже можно удалить.

---

## 2. Подавление двойных уведомлений

### Зачем

Нужно правило:

1. Если `rfm-downloader` сам отправляет итоговое уведомление, то запущенный из него `zenith-processor` не должен отправлять свое отдельное уведомление.
2. Если `zenith-processor` запускается отдельно в офисе, он должен отправлять уведомление по своему `zenith-config.json`.
3. Если в RFM уведомления выключены, но в Zenith включены, Zenith может отправить standalone-уведомление.

Для этого добавляем CLI-флаг:

```text
--suppress-notification
```

Этот флаг передается только при запуске Zenith из RFM, когда у RFM включены уведомления.

---

### 2.1. Добавить option в ZenithProcessorMain

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/ZenithProcessorMain.java
```

После поля:

```java
@Option(names = "--retry-failed", description = "Move one failed event back to new queue before processing")
private boolean retryFailed;
```

Добавить:

```java
@Option(names = "--suppress-notification", description = "Do not send Zenith notification for this run")
private boolean suppressNotification;
```

Метод `sendNotificationIfNeeded` заменить как указано в пункте 1.3.

---

### 2.2. Обновить ZenithTriggerConfig

Файл:

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/config/ZenithTriggerConfig.java
```

Заменить файл полностью:

```java
package org.ikozmin.rfm.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class ZenithTriggerConfig {
    @JsonProperty("Enabled")
    private boolean enabled;

    @JsonProperty("Command")
    private String command;

    @JsonProperty("WorkingDirectory")
    private String workingDirectory;

    @JsonProperty("TimeoutSeconds")
    private Integer timeoutSeconds;

    @JsonProperty("SuppressNotificationWhenRfmNotificationEnabled")
    private Boolean suppressNotificationWhenRfmNotificationEnabled;

    public boolean isEnabled() {
        return enabled;
    }

    public String getCommand() {
        return command;
    }

    public String getWorkingDirectory() {
        return workingDirectory == null || workingDirectory.isBlank()
                ? "."
                : workingDirectory;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds == null ? 1800 : timeoutSeconds;
    }

    public boolean isSuppressNotificationWhenRfmNotificationEnabled() {
        return suppressNotificationWhenRfmNotificationEnabled == null
                || suppressNotificationWhenRfmNotificationEnabled;
    }
}
```

---

### 2.3. Обновить ZenithProcessorTrigger

Файл:

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/trigger/ZenithProcessorTrigger.java
```

Заменить файл полностью:

```java
package org.ikozmin.rfm.trigger;

import org.ikozmin.rfm.config.ZenithTriggerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public final class ZenithProcessorTrigger {
    private static final Logger log = LoggerFactory.getLogger(ZenithProcessorTrigger.class);

    private final ZenithTriggerConfig config;

    public ZenithProcessorTrigger(ZenithTriggerConfig config) {
        this.config = config;
    }

    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    public void runOnce(boolean suppressNotification) {
        if (!isEnabled()) {
            return;
        }

        if (config.getCommand() == null || config.getCommand().isBlank()) {
            throw new IllegalStateException("ZenithTrigger.Command is empty");
        }

        try {
            Path workingDirectory = resolveWorkingDirectory(config.getWorkingDirectory());
            String command = buildCommand(config.getCommand(), suppressNotification);

            log.info("Starting zenith processor. workingDirectory={}, command={}",
                    workingDirectory,
                    command);

            Process process = new ProcessBuilder(
                    "cmd.exe",
                    "/d",
                    "/c",
                    command
            )
                    .directory(workingDirectory.toFile())
                    .inheritIO()
                    .start();

            boolean finished = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Zenith processor timeout: "
                        + Duration.ofSeconds(config.getTimeoutSeconds()));
            }

            if (process.exitValue() != 0) {
                throw new IllegalStateException("Zenith processor failed. exitCode=" + process.exitValue());
            }

            log.info("Zenith processor completed successfully");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to run zenith processor", e);
        }
    }

    private String buildCommand(String baseCommand, boolean suppressNotification) {
        if (!suppressNotification) {
            return baseCommand;
        }

        if (baseCommand.contains("--suppress-notification")) {
            return baseCommand;
        }

        return baseCommand + " --suppress-notification";
    }

    private Path resolveWorkingDirectory(String value) {
        Path path = Path.of(value);

        if (path.isAbsolute()) {
            return path.normalize();
        }

        String appHome = System.getProperty("app.home");

        if (appHome != null && !appHome.isBlank()) {
            return Path.of(appHome).resolve(path).normalize();
        }

        return Path.of(System.getProperty("user.dir")).resolve(path).normalize();
    }
}
```

Что изменилось:

1. Метод теперь принимает `suppressNotification`.
2. Если нужно, к команде добавляется `--suppress-notification`.
3. Удален закомментированный `parseCommand`, который сейчас не используется.

---

## 3. RFM должен запускать Zenith один раз после публикации всех событий

### Зачем

Сейчас в `Main` логика такая:

```text
скачали te21 -> создали event -> запустили zenith once
скачали un   -> создали event -> запустили zenith once
скачали mvk  -> создали event -> запустили zenith once
```

Это работает, но архитектурно хуже:

1. При нескольких обновлениях плодятся несколько запусков Zenith.
2. `run-zenith-once.bat` по смыслу берет только одно событие.
3. RFM уже умеет отправлять одно итоговое уведомление, значит и Zenith лучше запускать один раз после публикации всех событий.

Новая логика:

```text
скачали все обновленные реестры
создали все events
один раз запустили zenith drain
загрузили summary по каждому event
отправили одно итоговое уведомление RFM
```

---

### 3.1. В Main добавить внутренний record

Файл:

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/Main.java
```

В самый низ класса `Main`, перед последней закрывающей скобкой класса, добавить:

```java
private record PublishedRegistryUpdate(
        UpdateResult result,
        PublishedRegistryEvent event
) {
}
```

---

### 3.2. В методе run заменить список уведомлений

Найти:

```java
List<RegistryNotificationItem> notificationItems = new ArrayList<>();
```

Заменить на:

```java
List<PublishedRegistryUpdate> publishedUpdates = new ArrayList<>();
```

---

### 3.3. Внутри цикла заменить обработку downloaded

Найти внутри:

```java
if (result.isDownloaded()) {
```

и заменить в этом блоке только эту часть:

```java
PublishedRegistryEvent event = publishRegistryEvent(config, result);
runZenithProcessorIfNeeded(config, event.file());

Optional<ZenithProcessingSummary> zenithSummary = loadZenithSummary(config, event.eventId());
notificationItems.add(new RegistryNotificationItem(result, zenithSummary.orElse(null)));
```

на:

```java
PublishedRegistryEvent event = publishRegistryEvent(config, result);
publishedUpdates.add(new PublishedRegistryUpdate(result, event));
```

Остальной код блока `if (result.isDownloaded())` оставить как есть.

---

### 3.4. После цикла добавить один запуск Zenith и сбор notificationItems

Найти после завершения цикла:

```java
sendNotificationIfNeeded(config, notificationItems);
applyRetentionIfNeeded(config, workDir, downloadDir, catalogTypes);
```

Заменить на:

```java
runZenithProcessorIfNeeded(config, publishedUpdates);

List<RegistryNotificationItem> notificationItems = new ArrayList<>();

for (PublishedRegistryUpdate publishedUpdate : publishedUpdates) {
    Optional<ZenithProcessingSummary> zenithSummary = loadZenithSummary(
            config,
            publishedUpdate.event().eventId()
    );

    notificationItems.add(new RegistryNotificationItem(
            publishedUpdate.result(),
            zenithSummary.orElse(null)
    ));
}

sendNotificationIfNeeded(config, notificationItems);
applyRetentionIfNeeded(config, workDir, downloadDir, catalogTypes);
```

---

### 3.5. Заменить метод runZenithProcessorIfNeeded

Найти метод:

```java
private void runZenithProcessorIfNeeded(AppConfig config, Path eventFile) {
    ZenithProcessorTrigger trigger = new ZenithProcessorTrigger(config.getZenithTrigger());

    if (!trigger.isEnabled()) {
        log.info("Zenith trigger is disabled");
        return;
    }

    log.info("Zenith trigger enabled. eventFile={}", eventFile.toAbsolutePath());
    trigger.runOnce();
}
```

Заменить на:

```java
private void runZenithProcessorIfNeeded(AppConfig config, List<PublishedRegistryUpdate> publishedUpdates) {
    if (publishedUpdates == null || publishedUpdates.isEmpty()) {
        return;
    }

    ZenithProcessorTrigger trigger = new ZenithProcessorTrigger(config.getZenithTrigger());

    if (!trigger.isEnabled()) {
        log.info("Zenith trigger is disabled");
        return;
    }

    boolean suppressZenithNotification = config.getZenithTrigger() != null
            && config.getZenithTrigger().isSuppressNotificationWhenRfmNotificationEnabled()
            && config.getNotifications() != null
            && config.getNotifications().isEnabled();

    log.info("Zenith trigger enabled. events={}, suppressNotification={}",
            publishedUpdates.size(),
            suppressZenithNotification);

    trigger.runOnce(suppressZenithNotification);
}
```

После этого импорт `java.nio.file.Path` остается нужен в `Main`, поэтому его не удалять.

---

### 3.6. Изменить команду запуска Zenith в config.template.json

Файл:

```text
rfm-downloader/config/config.template.json
```

В блоке:

```json
"ZenithTrigger": {
  "Enabled": true,
  "Command": "run-zenith-once.bat --require-event --mode FULL",
  "WorkingDirectory": ".",
  "TimeoutSeconds": 1800
}
```

заменить на:

```json
"ZenithTrigger": {
  "Enabled": true,
  "Command": "run-zenith-drain.bat --mode FULL",
  "WorkingDirectory": ".",
  "TimeoutSeconds": 1800,
  "SuppressNotificationWhenRfmNotificationEnabled": true
}
```

Почему `drain`, а не `once`:

1. `once` берет одно событие.
2. `drain` обрабатывает все события, которые есть в очереди на момент запуска.
3. Это соответствует сценарию “RFM скачал несколько реестров, Zenith обработал всю пачку”.

Почему без `--require-event`:

1. RFM и так вызывает Zenith только если есть `publishedUpdates`.
2. Для `drain` отсутствие событий внутри второй итерации является нормальным завершением очереди.

---

## 4. Проверка выгрузки отчета Zenith

### Наблюдение

В текущем `ZenithReportService` папка создается:

```java
Path outputDir = Path.of(config.getOutputDirectory());
Files.createDirectories(outputDir);
```

Если папка не создалась, значит метод, скорее всего, не дошел до этой строки. Типовые причины:

1. Для каталога не найден `Reports.<catalog>` и отчет отключен fallback-конфигом.
2. Ошибка произошла на `apiClient.createReport(...)`.
3. Запуск идет из другой рабочей директории, и относительный путь смотрит не туда.
4. Фильтр не найден, и метод падает на `loadFilterXml()`.

Скрипты `run-rfm.bat` и `run-zenith-drain.bat` уже делают:

```bat
cd /d "%~dp0"
```

и задают:

```bat
-Dapp.home="%APP_HOME%"
```

Но `ZenithReportService` пока не использует `app.home` при разрешении относительных путей. Лучше добавить один локальный resolver.

---

### 4.1. Обновить ZenithReportService

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/service/ZenithReportService.java
```

Заменить метод `createAndDownloadReport` полностью:

```java
public ZenithReportResult createAndDownloadReport(String eventId, String catalog, String idXml) {
    try {
        LocalDate endDate = LocalDate.now();
        LocalDate beginDate = stateStore.loadLastSuccessfulCheckDate(catalog)
                .orElse(endDate);

        String filterXml = config.isFilter() ? loadFilterXml() : null;

        int outDocType = config.getOutDocType();

        log.info("Creating Zenith report. eventId={}, catalog={}, outDocType={}, beginDate={}, endDate={}, filterEnabled={}",
                eventId,
                catalog,
                outDocType,
                beginDate,
                endDate,
                config.isFilter());

        ZenithApiClient.ReportCreateData data = new ZenithApiClient.ReportCreateData(
                outDocType,
                ASSIGN_OUT_DOC_NUM,
                ALL_EMITENTS,
                beginDate.toString(),
                endDate.toString()
        );

        ZenithApiClient.OutDocLink outDoc = apiClient.createReport(
                data,
                filterXml
        );

        Path outputDir = resolveAppPath(config.getOutputDirectory());
        Files.createDirectories(outputDir);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yy_MM_dd");
        String fileName = config.getFileNamePrefix()
                + "_"
                + beginDate.format(formatter)
                + "-"
                + endDate.format(formatter)
                + "_"
                + catalog
                + ".xlsx";

        Path targetFile = outputDir.resolve(fileName);

        log.info("Downloading Zenith report. catalog={}, outDocId={}, targetFile={}",
                catalog,
                outDoc.id(),
                targetFile.toAbsolutePath());

        apiClient.downloadOutgoingDocument(outDoc.id(), REPORT_FORMAT, targetFile);

        stateStore.saveSuccessfulCheck(catalog, endDate, idXml, eventId);

        log.info("Zenith report downloaded. catalog={}, outDocId={}, file={}",
                catalog,
                outDoc.id(),
                targetFile.toAbsolutePath());

        return new ZenithReportResult(targetFile, beginDate, endDate, outDoc.id());
    } catch (Exception e) {
        throw new IllegalStateException("Failed to create and download Zenith report. catalog="
                + catalog
                + ", outputDirectory="
                + config.getOutputDirectory(), e);
    }
}
```

Заменить метод `loadFilterXml` полностью:

```java
private String loadFilterXml() throws Exception {
    Path filterPath = resolveAppPath(config.getFilterTemplatePath());

    if (!Files.isRegularFile(filterPath)) {
        throw new IllegalStateException("Zenith report filter file not found: "
                + filterPath.toAbsolutePath());
    }

    log.info("Loading Zenith report filter: {}", filterPath.toAbsolutePath());

    return Files.readString(filterPath);
}
```

После `loadFilterXml` добавить новый метод:

```java
private Path resolveAppPath(String value) {
    Path path = Path.of(value);

    if (path.isAbsolute()) {
        return path.normalize();
    }

    String appHome = System.getProperty("app.home");

    if (appHome != null && !appHome.isBlank()) {
        return Path.of(appHome).resolve(path).normalize();
    }

    return Path.of(System.getProperty("user.dir")).resolve(path).normalize();
}
```

Что это даст:

1. В логе будет видно, какой `outDocType`, даты и фильтр реально ушли в Zenith.
2. В логе будет абсолютный путь, куда программа пытается сохранить отчет.
3. Относительные пути будут привязаны к папке приложения, а не к случайной текущей директории процесса.

---

## 5. Логи Zenith и “архивирование”

### Важное уточнение

Текущий `logback.xml`:

```xml
<file>${LOG_FILE}</file>
<fileNamePattern>${LOG_DIR}/zenith-processor.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
```

работает штатно для Logback:

1. Активный файл всегда называется `zenith-processor.log`.
2. При ротации старый активный файл переименовывается в `zenith-processor.2026-07-07.0.log`.
3. Новый активный файл снова называется `zenith-processor.log`.

То, что после ротации появился `zenith-processor.2026-07-07.0.log` и новый `zenith-processor.log`, само по себе не ошибка. Это нормальная схема rolling file.

Если под “архивированием” нужно именно сжатие старых логов, тогда менять надо только pattern.

---

### 5.1. Вариант с gzip-архивами

Файл:

```text
zenith-processor/src/main/resources/logback.xml
```

Найти:

```xml
<fileNamePattern>${LOG_DIR}/zenith-processor.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
```

Заменить на:

```xml
<fileNamePattern>${LOG_DIR}/zenith-processor.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
```

Это даст:

```text
logs/zenith-processor.log
logs/zenith-processor.2026-07-07.0.log.gz
logs/zenith-processor.2026-07-08.0.log.gz
```

Активный файл `zenith-processor.log` останется. Это удобно для просмотра текущего запуска.

---

### 5.2. Если лог иногда продолжает писаться в старый файл

Это обычно происходит не из-за `logback.xml`, а из-за параллельных процессов:

1. Старый процесс стартовал до полуночи и держит файл.
2. Новый процесс стартовал после полуночи.
3. Оба пишут в один и тот же набор файлов.

В планировщике Windows для задачи Zenith и RFM проверить настройку:

```text
Если задача уже выполняется: не запускать новый экземпляр
```

Если включен режим `watch`, его нельзя запускать каждый час второй копией. Для планировщика лучше использовать `drain`, а `watch` держать как отдельный постоянный сервис только в одном экземпляре.

---

## 6. Проверка после правок

### 6.1. Сборка

Из корня проекта:

```bat
mvn clean package
```

---

### 6.2. Проверка RFM плюс Zenith

В `config/config.json` для центрального запуска:

```json
"Notifications": {
  "Enabled": true
}
```

и:

```json
"ZenithTrigger": {
  "Enabled": true,
  "Command": "run-zenith-drain.bat --mode FULL",
  "WorkingDirectory": ".",
  "TimeoutSeconds": 1800,
  "SuppressNotificationWhenRfmNotificationEnabled": true
}
```

Запуск:

```bat
run-rfm.bat
```

Ожидаемый результат:

1. Если обновился один реестр, приходит одно итоговое уведомление RFM.
2. Если обновились несколько реестров, приходит одно итоговое уведомление RFM со всеми реестрами.
3. Отдельное уведомление от Zenith не приходит, если RFM-уведомление включено.
4. Summary Zenith подгружается в итоговое RFM-уведомление по каждому event.

---

### 6.3. Проверка standalone Zenith

В офисном `config/zenith-config.json`:

```json
"Notifications": {
  "Enabled": true
}
```

Запуск:

```bat
run-zenith-drain.bat --mode CHECK_ONLY
```

Ожидаемый результат:

1. Zenith берет все события из своей `CheckDirectory`.
2. По каждому событию выполняет проверку.
3. По каждому событию сохраняет summary.
4. Если включены уведомления, Zenith отправляет свое standalone-уведомление.

---

### 6.4. Проверка антидубля

Сценарий 1:

```text
RFM Notifications.Enabled=true
Zenith Notifications.Enabled=true
Запуск через run-rfm.bat
```

Ожидание:

```text
Приходит только итоговое уведомление RFM.
Zenith standalone-уведомление подавлено флагом --suppress-notification.
```

Сценарий 2:

```text
RFM Notifications.Enabled=false
Zenith Notifications.Enabled=true
Запуск через run-rfm.bat
```

Ожидание:

```text
RFM не отправляет уведомление.
Zenith может отправить standalone-уведомление, потому что suppress-флаг не добавляется.
```

Сценарий 3:

```text
Офисный запуск run-zenith-drain.bat --mode CHECK_ONLY
Zenith Notifications.Enabled=true
```

Ожидание:

```text
Zenith отправляет уведомление.
```

---

## 7. Что уже не надо делать

Не надо возвращать старую схему:

```text
run-zenith-once.bat --require-event --mode FULL
```

для запуска из RFM. Для центрального запуска с несколькими реестрами нужен:

```text
run-zenith-drain.bat --mode FULL
```

Не надо оставлять два разных builder-а текста Zenith. Один источник текста должен быть в:

```text
common/src/main/java/org/ikozmin/common/notification/ZenithNotificationTextBuilder.java
```

Не надо включать центральные Zenith-уведомления как основной механизм, если RFM уже отправляет итоговое уведомление. Центральный Zenith может иметь уведомления включенными в конфиге, но при запуске из RFM они должны подавляться через `--suppress-notification`.
