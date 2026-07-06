# План реализации по TODO

Документ описывает, что нужно изменить в текущем рабочем проекте.

Код автоматически не менялся. Инструкция предназначена для ручного внесения правок.

Корень Maven-проекта:

```text
G:\tmp\fedfsm\java
```

## Принятые решения

1. Пункты про читаемость уведомления и размер файла считаем закрытыми, так как уведомления у пользователя отображаются корректно.
2. Информацию о количестве добавленных/измененных/удаленных записей после импорта в Zenith сейчас не реализуем: текущий API загрузки возвращает пустой `200`, надежного источника этих данных нет.
3. На fat/uber jar не переходим. Из-за CryptoPro безопаснее оставить portable distribution: отдельные jar-файлы, `libs/`, `config/`, portable JDK рядом с приложением.
4. Проект должен запускаться из любого текущего каталога и не должен ломаться после переноса папки приложения.

## Целевая структура приложения

```text
rfm-automation/
  jdk-21.0.10/
  rfm-downloader.jar
  zenith-processor.jar
  libs/
    *.jar
    CryptoPro *.jar
  config/
    config.json
    zenith-config.json
    zenith/
      podft-report-filter.xml
  run-rfm.bat
  run-zenith-once.bat
  run-zenith-retry-failed.bat
  downloads/
  events/
  data/
  logs/
```

Все runtime-пути должны считаться относительно папки приложения.

---

## 1. Упаковка CryptoPro jar в ZIP

### Проблема

CryptoPro jar лежат в:

```text
rfm-downloader/libs/
```

и подключены как `system` dependencies. Такие зависимости не стоит полагаться получать через обычный `copy-dependencies`.

### Файл

```text
distribution/src/main/assembly/distribution.xml
```

### Что добавить

Внутрь `<fileSets>` после блока `${project.build.directory}/libs` добавить:

```xml
<fileSet>
    <directory>${project.parent.basedir}/rfm-downloader/libs</directory>
    <outputDirectory>libs</outputDirectory>
    <includes>
        <include>*.jar</include>
    </includes>
</fileSet>
```

### Зачем

Чтобы в итоговый ZIP гарантированно попали:

```text
libs/JCSP.jar
libs/JCP.jar
libs/ASN1P.jar
libs/asn1rt.jar
libs/JCPRevCheck.jar
libs/cpSSL.jar
libs/JCryptoP.jar
libs/JCPxml.jar
```

---

## 2. Исключить лишние запускаемые jar из libs

### Проблема

В `libs/` могут попадать:

```text
rfm-downloader-2.1.2.jar
zenith-processor-2.1.2.jar
```

Они не нужны, потому что в корне ZIP уже есть:

```text
rfm-downloader.jar
zenith-processor.jar
```

### Файл

```text
distribution/pom.xml
```

### Что заменить

В `maven-dependency-plugin` найти:

```xml
<configuration>
    <outputDirectory>${project.build.directory}/libs</outputDirectory>
    <includeScope>runtime</includeScope>
</configuration>
```

Заменить на:

```xml
<configuration>
    <outputDirectory>${project.build.directory}/libs</outputDirectory>
    <includeScope>runtime</includeScope>
    <excludeArtifactIds>rfm-downloader,zenith-processor</excludeArtifactIds>
</configuration>
```

### Зачем

Убрать дубли запускаемых jar из `libs/`, но оставить `common` и внешние библиотеки.

---

## 3. Portable Java в run-rfm.bat

### Файл

```text
run-rfm.bat
```

### Заменить полностью

```bat
@echo off
chcp 65001 > nul
setlocal
cd /d "%~dp0"

set "APP_HOME=%~dp0"
set "JAVA_HOME=%APP_HOME%jdk-21.0.10"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo Java not found: "%JAVA_HOME%\bin\java.exe"
  exit /b 2
)

set "PATH=C:\Program Files\Crypto Pro\CSP;%JAVA_HOME%\bin;%PATH%"

"%JAVA_HOME%\bin\java.exe" ^
  -Dapp.home="%APP_HOME%" ^
  -Djava.library.path="C:\Program Files\Crypto Pro\CSP" ^
  -Dcom.sun.net.ssl.checkRevocation=false ^
  -Djavax.net.ssl.trustStore=NONE ^
  -Djavax.net.ssl.trustStoreType=Windows-ROOT ^
  -cp "rfm-downloader.jar;libs\*" ^
  org.ikozmin.rfm.Main ^
  --config config\config.json ^
  --prod ^
  --catalog te21

exit /b %ERRORLEVEL%
```

### Зачем

Java берется из папки приложения:

```text
<app>\jdk-21.0.10
```

Запуск работает из любого текущего каталога.

---

## 4. Portable Java в run-zenith-once.bat

### Файл

```text
run-zenith-once.bat
```

### Заменить полностью

```bat
@echo off
chcp 65001 > nul
setlocal
cd /d "%~dp0"

set "APP_HOME=%~dp0"
set "JAVA_HOME=%APP_HOME%jdk-21.0.10"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo Java not found: "%JAVA_HOME%\bin\java.exe"
  exit /b 2
)

"%JAVA_HOME%\bin\java.exe" ^
  -Dapp.home="%APP_HOME%" ^
  -cp "zenith-processor.jar;libs\*" ^
  org.ikozmin.zenith.ZenithProcessorMain ^
  --config config\zenith-config.json ^
  --once ^
  %*

exit /b %ERRORLEVEL%
```

### Зачем

Zenith использует тот же portable JDK.

`%*` позволяет передавать дополнительные параметры:

```bat
run-zenith-once.bat --require-event
run-zenith-once.bat --retry-failed
```

---

## 5. Рабочая папка для запуска Zenith из rfm-downloader

### Проблема

Если `run-rfm.bat` запущен из другого каталога, Java-процесс может не найти `run-zenith-once.bat`, если запускать его как простую относительную команду.

### Решение

Добавить `WorkingDirectory` в конфиг и резолвить его относительно `app.home`.

---

### 5.1. ZenithTriggerConfig

Файл:

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/config/ZenithTriggerConfig.java
```

Заменить полностью:

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

    public Integer getTimeoutSeconds() {
        return timeoutSeconds == null ? 1000 : timeoutSeconds;
    }
}
```

---

### 5.2. ZenithProcessorTrigger

Файл:

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/trigger/ZenithProcessorTrigger.java
```

Заменить полностью:

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

    public void runOnce() {
        if (!isEnabled()) {
            return;
        }

        if (config.getCommand() == null || config.getCommand().isBlank()) {
            throw new IllegalStateException("ZenithTrigger.Command is empty");
        }

        try {
            Path workingDirectory = resolveWorkingDirectory(config.getWorkingDirectory());

            log.info("Starting zenith processor. workingDirectory={}, command={}",
                    workingDirectory,
                    config.getCommand());

            Process process = new ProcessBuilder(
                    "cmd.exe",
                    "/d",
                    "/c",
                    config.getCommand()
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

---

## 6. Обновить ZenithTrigger в config.json

Файлы:

```text
rfm-downloader/config.json
rfm-downloader/config.template.json
```

Заменить блок:

```json
"ZenithTrigger": {
  "Enabled": true,
  "Command": "run-zenith-once.bat",
  "TimeoutSeconds": 1800
}
```

на:

```json
"ZenithTrigger": {
  "Enabled": true,
  "Command": "run-zenith-once.bat --require-event",
  "WorkingDirectory": ".",
  "TimeoutSeconds": 1800
}
```

### Зачем

Когда `rfm-downloader` только что создал event, отсутствие event для Zenith - это ошибка. Поэтому автоматический запуск использует `--require-event`.

---

## 7. Добавить режим --require-event

### Файл

```text
zenith-processor/src/main/java/org/ikozmin/zenith/ZenithProcessorMain.java
```

### Добавить поле

После:

```java
@Option(names = "--once", description = "Process one event and exit")
private boolean once;
```

добавить:

```java
@Option(names = "--require-event", description = "Fail if no event is available")
private boolean requireEvent;
```

### Заменить блок

Найти:

```java
if (claimedEvent.isEmpty()) {
    log.info("No registry update events found");
    return 0;
}
```

Заменить на:

```java
if (claimedEvent.isEmpty()) {
    if (requireEvent) {
        log.error("No registry update events found, but event is required");
        System.err.println("No registry update events found, but event is required");
        return 3;
    }

    log.info("No registry update events found");
    return 0;
}
```

### Зачем

Ручной запуск без events остается нормальным, автоматический запуск после РФМ должен падать, если event не найден.

---

## 8. Улучшить текст при отсутствии Zenith summary

### Файл

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/service/UnifiedNotificationTextBuilder.java
```

### Что заменить

Найти:

```java
body.append(indent).append("Результат Zenith недоступен. Проверьте журнал zenith-processor.").append(System.lineSeparator());
```

Заменить на:

```java
body.append(indent).append("Zenith не вернул файл результата обработки.").append(System.lineSeparator());
body.append(indent).append("Проверьте logs/zenith-processor.log и каталог events/registry-updated.").append(System.lineSeparator());
```

### Зачем

Текст становится точнее: Zenith мог запуститься, но не создать summary.

---

## 9. Добавить лог Zenith

### Создать файл

```text
zenith-processor/src/main/resources/logback.xml
```

### Код

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="LOG_DIR" value="logs"/>
    <property name="LOG_FILE" value="${LOG_DIR}/zenith-processor.log"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <charset>UTF-8</charset>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_FILE}</file>

        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_DIR}/zenith-processor.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
            <maxFileSize>10MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>300MB</totalSizeCap>
        </rollingPolicy>

        <encoder>
            <charset>UTF-8</charset>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} %-5level %logger{48} - %msg%n</pattern>
        </encoder>
    </appender>

    <logger name="org.ikozmin.zenith" level="INFO"/>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

### Результат

Появится файл:

```text
logs/zenith-processor.log
```

---

## 10. Что делать с архивированием логов

### Замечание

Если в `logback.xml` заменить:

```xml
<fileNamePattern>${LOG_DIR}/rfm-client.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
```

на:

```xml
<fileNamePattern>${LOG_DIR}/rfm-client.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
```

то **не только вчерашний**, но и позавчерашний, и все старые логи в пределах `maxHistory` останутся неархивированными.

Это нормально, если важнее быстро читать логи.

### Вариант A, рекомендую для простоты

Оставить все старые логи неархивированными:

```xml
<fileNamePattern>${LOG_DIR}/rfm-client.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
```

Ограничение размера уже есть:

```xml
<maxHistory>30</maxHistory>
<totalSizeCap>300MB</totalSizeCap>
```

### Вариант B, если нужна экономия места

Оставить `.gz`, но принять, что вчерашний лог утром надо открывать как архив.

### Вариант C, сложнее

Оставить логи за последние 1-2 дня `.log`, а более старые архивировать отдельной задачей Windows/PowerShell.

Для текущего проекта я бы выбрал **вариант A**.

---

## 11. Retention для events

### Проблема

Каталоги:

```text
events/registry-updated/processed
events/registry-updated/failed
events/registry-updated/results
```

будут расти бесконечно.

---

### 11.1. RetentionConfig

Файл:

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/config/RetentionConfig.java
```

Заменить полностью:

```java
package org.ikozmin.rfm.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class RetentionConfig {
    @JsonProperty("Enabled")
    private boolean enabled;

    @JsonProperty("KeepAuditDays")
    private Integer keepAuditDays;

    @JsonProperty("KeepDownloadedVersions")
    private Integer keepDownloadedVersions;

    @JsonProperty("KeepProcessedEventDays")
    private Integer keepProcessedEventDays;

    @JsonProperty("KeepFailedEventDays")
    private Integer keepFailedEventDays;

    @JsonProperty("KeepResultEventDays")
    private Integer keepResultEventDays;

    public boolean isEnabled() {
        return enabled;
    }

    public int getKeepAuditDays() {
        return keepAuditDays == null ? 30 : keepAuditDays;
    }

    public int getKeepDownloadedVersions() {
        return keepDownloadedVersions == null ? 10 : keepDownloadedVersions;
    }

    public int getKeepProcessedEventDays() {
        return keepProcessedEventDays == null ? 30 : keepProcessedEventDays;
    }

    public int getKeepFailedEventDays() {
        return keepFailedEventDays == null ? 180 : keepFailedEventDays;
    }

    public int getKeepResultEventDays() {
        return keepResultEventDays == null ? 30 : keepResultEventDays;
    }
}
```

---

### 11.2. EventRetentionService

Создать файл:

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/service/EventRetentionService.java
```

Код:

```java
package org.ikozmin.rfm.service;

import org.ikozmin.rfm.config.RetentionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;

public final class EventRetentionService {
    private static final Logger log = LoggerFactory.getLogger(EventRetentionService.class);

    private final RetentionConfig config;

    public EventRetentionService(RetentionConfig config) {
        this.config = config;
    }

    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    public void apply(Path eventRootDir) {
        if (!isEnabled()) {
            return;
        }

        clean(eventRootDir.resolve("processed"), config.getKeepProcessedEventDays());
        clean(eventRootDir.resolve("results"), config.getKeepResultEventDays());
        clean(eventRootDir.resolve("failed"), config.getKeepFailedEventDays());
    }

    private void clean(Path directory, int keepDays) {
        if (!Files.isDirectory(directory)) {
            return;
        }

        Instant threshold = Instant.now().minus(keepDays, ChronoUnit.DAYS);

        try (Stream<Path> files = Files.list(directory)) {
            files
                    .filter(Files::isRegularFile)
                    .filter(file -> isOlderThan(file, threshold))
                    .forEach(this::deleteQuietly);
        } catch (Exception e) {
            log.warn("Event retention failed. dir={}, error={}", directory, e.getMessage());
        }
    }

    private boolean isOlderThan(Path file, Instant threshold) {
        try {
            return Files.getLastModifiedTime(file).toInstant().isBefore(threshold);
        } catch (Exception e) {
            return false;
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
            log.info("Event retention deleted file: {}", file.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to delete event file by retention. file={}, error={}",
                    file,
                    e.getMessage());
        }
    }
}
```

---

### 11.3. Main

Файл:

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/Main.java
```

В методе `applyRetentionIfNeeded` после:

```java
retentionService.apply(workDir, downloadDir, catalogType);
```

добавить:

```java
Path eventRootDir = Path.of(config.getEvents() == null
        ? "events/registry-updated"
        : config.getEvents().getDirectory()
);

new EventRetentionService(config.getRetention()).apply(eventRootDir);
```

---

### 11.4. config.json и template

В `Retention` добавить:

```json
"KeepProcessedEventDays": 30,
"KeepFailedEventDays": 180,
"KeepResultEventDays": 30
```

Итог:

```json
"Retention": {
  "Enabled": true,
  "KeepAuditDays": 60,
  "KeepDownloadedVersions": 10,
  "KeepProcessedEventDays": 30,
  "KeepFailedEventDays": 180,
  "KeepResultEventDays": 30
}
```

---

## 12. Ручная обработка failed events

Автоматически возвращать failed events в обработку не надо: если причина ошибки не устранена, событие будет падать бесконечно.

Делаем ручной режим:

```text
run-zenith-retry-failed.bat
```

---

### 12.1. FileEventConsumer

Файл:

```text
common/src/main/java/org/ikozmin/common/event/FileEventConsumer.java
```

Добавить метод в класс:

```java
public Optional<Path> requeueOldestFailed() {
    try {
        Files.createDirectories(newDir);
        Files.createDirectories(failedDir);

        try (Stream<Path> files = Files.list(failedDir)) {
            Optional<Path> next = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .findFirst();

            if (next.isEmpty()) {
                return Optional.empty();
            }

            Path source = next.get();
            Path target = newDir.resolve(source.getFileName());

            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);

            return Optional.of(target);
        }
    } catch (Exception e) {
        throw new IllegalStateException("Failed to requeue failed event", e);
    }
}
```

---

### 12.2. ZenithProcessorMain

Файл:

```text
zenith-processor/src/main/java/org/ikozmin/zenith/ZenithProcessorMain.java
```

После:

```java
@Option(names = "--once", description = "Process one event and exit")
private boolean once;
```

добавить:

```java
@Option(names = "--retry-failed", description = "Move one failed event back to new queue before processing")
private boolean retryFailed;
```

После создания consumer:

```java
FileEventConsumer consumer = new FileEventConsumer(Path.of(config.getEvents().getDirectory()));
```

добавить:

```java
if (retryFailed) {
    Optional<Path> requeued = consumer.requeueOldestFailed();

    if (requeued.isEmpty()) {
        log.info("No failed registry update events found");
        return 0;
    }

    log.info("Failed event requeued: {}", requeued.get().toAbsolutePath());
}
```

---

### 12.3. run-zenith-retry-failed.bat

Создать файл:

```text
run-zenith-retry-failed.bat
```

Код:

```bat
@echo off
chcp 65001 > nul
setlocal
cd /d "%~dp0"

set "APP_HOME=%~dp0"
set "JAVA_HOME=%APP_HOME%jdk-21.0.10"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo Java not found: "%JAVA_HOME%\bin\java.exe"
  exit /b 2
)

"%JAVA_HOME%\bin\java.exe" ^
  -Dapp.home="%APP_HOME%" ^
  -cp "zenith-processor.jar;libs\*" ^
  org.ikozmin.zenith.ZenithProcessorMain ^
  --config config\zenith-config.json ^
  --retry-failed ^
  --once

exit /b %ERRORLEVEL%
```

---

### 12.4. distribution.xml

В блок скриптов добавить:

```xml
<include>run-zenith-retry-failed.bat</include>
```

Было:

```xml
<include>run-rfm.bat</include>
<include>run-zenith-once.bat</include>
```

Должно быть:

```xml
<include>run-rfm.bat</include>
<include>run-zenith-once.bat</include>
<include>run-zenith-retry-failed.bat</include>
```

---

## 13. Толстый клиент

Fat/uber jar не делаем.

Причина: CryptoPro jar могут иметь особенности загрузки, подписи, service descriptors и provider-логику. Упаковка их внутрь одного jar повышает риск проблем.

Оставляем portable distribution:

```text
jdk-21.0.10/
rfm-downloader.jar
zenith-processor.jar
libs/
config/
run-rfm.bat
```

Это дает тот же эксплуатационный эффект: папку можно перенести, и приложение продолжит работать.

---

## Проверка после реализации

Сборка:

```bat
mvn -q clean package
```

Проверить ZIP:

```text
target/distr/rfm-automation-2.1.2.zip
```

Внутри должно быть:

```text
rfm-downloader.jar
zenith-processor.jar
libs/
  common-2.1.2.jar
  jackson-*.jar
  poi-*.jar
  jakarta.mail-*.jar
  JCSP.jar
  JCP.jar
  ASN1P.jar
  asn1rt.jar
  JCPRevCheck.jar
  cpSSL.jar
  JCryptoP.jar
  JCPxml.jar
config/
run-rfm.bat
run-zenith-once.bat
run-zenith-retry-failed.bat
```

Не должно быть в `libs/`:

```text
rfm-downloader-2.1.2.jar
zenith-processor-2.1.2.jar
```

Проверить запуск:

```bat
run-rfm.bat
```

Запуск должен работать:

1. из папки приложения;
2. из любого другого текущего каталога;
3. после переноса всей папки приложения в другое место.

Проверить логи:

```text
logs/rfm-client.log
logs/zenith-processor.log
```

Проверить события:

```text
events/registry-updated/new
events/registry-updated/processing
events/registry-updated/processed
events/registry-updated/failed
events/registry-updated/results
```

