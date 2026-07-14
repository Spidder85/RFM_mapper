# Актуальная инструкция по незавершенным доработкам

Дата сверки: 2026-07-13.

Документ содержит только пункты, которые еще требуют изменения или проверки. Уже реализованные части из старого документа удалены, чтобы не предлагать выполнить их повторно.

## Что уже реализовано и не нужно переделывать

1. `zenith-processor --drain` продолжает работу после ошибки одного события: проблемное событие переносится в `failed`, следующие остаются доступными для обработки.
2. Ответ Zenith о том, что импортируемый список старее уже загруженного, распознается как штатно пропущенное событие (`skipped`).
3. Отсутствие листа `Таблица_Проверок` в XLSX означает пустой результат, а не ошибку.
4. Полный workflow публикует `ZenithImportCompleted` для офисных очередей после успешного импорта.
5. Завершенные events (`processed`, `failed`, `results`) удаляются через 30 дней. Каталоги `new` и `processing` намеренно не очищаются автоматически.
6. Текст результатов Zenith уже вынесен в общий `common`-класс `ZenithNotificationTextBuilder` и используется как отдельным Zenith-уведомлением, так и в общем уведомлении RFM.
7. Во все production-классы добавлены краткие Javadoc-комментарии; в точках входа и бизнес-сервисах прокомментированы ключевые методы.

## 1. Зафиксировать правило единственного уведомления

### Текущее поведение

Дублирование уже предотвращается кодом `rfm-downloader`:

```text
RFM Notifications.Enabled = true
и
ZenithTrigger.SuppressNotificationWhenRfmNotificationEnabled = true
```

Тогда RFM запускает Zenith с параметром `--suppress-notification`, Zenith сохраняет summary, а итоговое письмо или Telegram-сообщение отправляет только RFM.

Если RFM-уведомления выключены, автономный Zenith может отправить собственное уведомление по своей конфигурации.

### Что изменить

Файл:

```text
rfm-downloader/config/config.template.json
```

В существующий объект `ZenithTrigger` добавить явную настройку. Весь объект после изменения должен выглядеть так:

```json
"ZenithTrigger": {
  "Enabled": true,
  "Command": "run-zenith-drain.bat --mode FULL",
  "WorkingDirectory": ".",
  "TimeoutSeconds": 1800,
  "SuppressNotificationWhenRfmNotificationEnabled": true
}
```

Зачем: значение и без того по умолчанию равно `true`, но явная настройка делает правило понятным сотруднику, который будет сопровождать конфиг.

Рабочий `config.json` менять аналогично только при необходимости явного документирования. Логику Java менять не нужно.

## 2. Исправить формат и ротацию лога Zenith

Сейчас `zenith-processor` пишет ротационные файлы с расширением `.log` и выводит имя класса logger. Требование TODO: архивировать старые логи и оставлять только важность с текстом.

Файл:

```text
zenith-processor/src/main/resources/logback.xml
```

Заменить файл полностью:

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<configuration>
    <property name="LOG_DIR" value="logs"/>
    <property name="LOG_FILE" value="${LOG_DIR}/zenith-processor.log"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <charset>UTF-8</charset>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} %-5level - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_FILE}</file>

        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_DIR}/zenith-processor.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>10MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>300MB</totalSizeCap>
        </rollingPolicy>

        <encoder>
            <charset>UTF-8</charset>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} %-5level - %msg%n</pattern>
        </encoder>
    </appender>

    <logger name="org.ikozmin.zenith" level="INFO"/>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

Результат:

```text
logs/zenith-processor.log
logs/zenith-processor.2026-07-13.0.log.gz
```

После ротации старый файл будет сжат, активный всегда остается `zenith-processor.log`. Имя Java-класса в строку лога не выводится.

## 3. Дополнить итог drain информацией о failed-событиях

Код уже не прерывает очередь при ошибке одного события, но в завершающем сообщении не показывает число ошибок.

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/ZenithProcessorMain.java
```

Найти метод:

```java
private Integer processDrain(ZenithConfig config, ZenithWorkflowMode workflowMode) {
```

Заменить метод полностью:

```java
private Integer processDrain(ZenithConfig config, ZenithWorkflowMode workflowMode) {
    int processed = 0;
    int failed = 0;

    while (true) {
        int exitCode = processOnce(config, workflowMode, false);

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
}
```

Зачем: по одной итоговой строке журнала сразу видно, была ли очередь обработана полностью без ошибок. Код завершения `drain` остается `0`: это корректно, потому что очередь разобрана, а ошибочные события уже лежат в `failed`.

## 4. Проверить сохранение XLSX-отчета, не меняя код без подтвержденной ошибки

Код `ZenithReportService` уже делает необходимое:

```java
Path outputDir = resolveAppPath(config.getOutputDirectory());
Files.createDirectories(outputDir);
Path targetFile = outputDir.resolve(fileName);
apiClient.downloadOutgoingDocument(outDoc.id(), REPORT_FORMAT, targetFile);
```

Поэтому сначала проверить фактическую конфигурацию и журнал, а не менять реализацию.

Для рабочего `zenith-config.json` у нужного каталога должен быть заполнен блок, например:

```json
"te21": {
  "Enabled": true,
  "OutDocType": 10217,
  "Filter": true,
  "FilterTemplatePath": "config/zenith/podft-report-filter-te21.xml",
  "OutputDirectory": "downloads/zenith-reports/te21",
  "FileNamePrefix": "T38_terr"
}
```

Относительный `OutputDirectory` разрешается от `app.home`, который задают bat-скрипты. После запуска искать отчет нужно в:

```text
<папка zenith-processor>/downloads/zenith-reports/te21
```

Если каталога нет, в логе должна быть строка `Creating Zenith report` или ошибка до нее. Если строка есть, но файла нет, приложить фрагмент лога от `Creating Zenith report` до `Zenith report downloaded`.

## 5. Важная проверка шаблона BaseUrl

`ZenithApiClient` самостоятельно добавляет путь `/zenith-object/api/...`. Поэтому значение `Zenith.BaseUrl` не должно уже содержать `/zenith-object`.

Файл:

```text
zenith-processor/config/zenith-config.template.json
```

Заменить:

```json
"BaseUrl": "https://zenith-server/zenith-object"
```

на:

```json
"BaseUrl": "https://zenith-server"
```

И аналогично проверить рабочий `zenith-config.json`.

Иначе итоговый URL станет ошибочным:

```text
https://zenith-server/zenith-object/zenith-object/api/...
```

## 6. Проверка после изменений

Из корня Maven-проекта:

```bat
mvn clean package
```

Проверить:

1. Запуск `run-zenith-drain.bat --mode FULL` обрабатывает все события из `new`.
2. Ошибочное событие оказывается в `failed`, а следующие обрабатываются.
3. В конце есть строка `Zenith drain completed. processedEvents=..., failedEvents=...`.
4. При запуске из RFM включены оба блока `Notifications`, но приходит только одно итоговое уведомление от RFM.
5. При автономном запуске Zenith и выключенных уведомлениях RFM приходит только уведомление Zenith.
6. После следующей даты старый лог имеет имя `zenith-processor.YYYY-MM-DD.N.log.gz`.
7. Отчет находится в каталоге из `Reports.<catalog>.OutputDirectory`.
