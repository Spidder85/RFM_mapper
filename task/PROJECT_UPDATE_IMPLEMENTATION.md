# Retention: события, реестры, отчеты и логи

Дата: 2026-07-21.

## Решение

| Данные | Срок хранения |
|---|---|
| Скачанные реестры | Бессрочно по умолчанию |
| XLSX-отчеты Zenith | Бессрочно |
| Черновики ФЭС | Бессрочно |
| RFM audit | `KeepAuditDays` |
| Логи RFM и Zenith | 30 дней |
| `processed`, `failed`, `results` events | 30 дней |
| `new`, `processing`, `retry` events | Не удаляются автоматически |

## Текущее состояние

1. `EventRetentionService` уже удаляет только `processed`, `failed`, `results` старше 30 дней. Он не касается XLSX-отчетов.
2. `ZenithProcessorMain.applyEventRetention(...)` уже вызывается после `once`/`drain` и после каждой итерации `watch`. Поэтому events Zenith не накапливаются бесконечно.
3. В обоих `logback.xml` задано `maxHistory=30` и `totalSizeCap=300MB`: архивы логов очищаются автоматически.
4. Реальная проблема только одна: RFM очищает events внутри `applyRetentionIfNeeded(...)`, а тот сразу завершается при `Retention.Enabled=false`. Очистку events надо отделить от хранения реестров и audit.

## 1. Бессрочное хранение реестров

### 1.1. Файл RetentionConfig

Файл:

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/config/RetentionConfig.java
```

Заменить файл полностью:

```java
package org.ikozmin.rfm.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Настройки хранения audit и скачанных реестров.
 * KeepDownloadedVersions <= 0 означает бессрочное хранение реестров.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class RetentionConfig {
    @JsonProperty("Enabled")
    private boolean enabled;

    @JsonProperty("KeepAuditDays")
    private Integer keepAuditDays;

    @JsonProperty("KeepDownloadedVersions")
    private Integer keepDownloadedVersions;

    public boolean isEnabled() {
        return enabled;
    }

    public int getKeepAuditDays() {
        return keepAuditDays == null ? 30 : keepAuditDays;
    }

    /**
     * Возвращает число хранимых версий; 0 означает не удалять реестры.
     */
    public int getKeepDownloadedVersions() {
        return keepDownloadedVersions == null ? 0 : keepDownloadedVersions;
    }
}
```

Поля `KeepProcessedEventDays`, `KeepFailedEventDays`, `KeepResultEventDays` удалить: они больше не используются. Для завершенных событий всегда применяется общий срок 30 дней.

### 1.2. Файл RetentionService

Файл:

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/service/RetentionService.java
```

Заменить метод `cleanDownloadedVersions(...)` полностью:

```java
/**
 * Удаляет старые версии только при положительном лимите.
 * Ноль и отрицательные значения означают бессрочное хранение реестров.
 */
private void cleanDownloadedVersions(Path downloadDir, CatalogType catalogType) {
    if (!Files.isDirectory(downloadDir)) {
        return;
    }

    int keep = config.getKeepDownloadedVersions();

    if (keep <= 0) {
        log.info("Downloaded registry retention is disabled. catalog={}", catalogType.getCode());
        return;
    }

    String expectedPrefix = catalogType.getFilePrefix() + "_";
    String expectedSuffix = "." + catalogType.getExtension();

    try (Stream<Path> files = Files.walk(downloadDir)) {
        List<Path> registryFiles = files
                .filter(Files::isRegularFile)
                .filter(file -> {
                    String name = file.getFileName().toString();
                    return name.startsWith(expectedPrefix) && name.endsWith(expectedSuffix);
                })
                .sorted(Comparator.comparing(this::lastModified).reversed())
                .toList();

        if (registryFiles.size() <= keep) {
            return;
        }

        registryFiles.stream()
                .skip(keep)
                .forEach(this::deleteQuietly);
    } catch (Exception e) {
        log.warn("Downloaded files retention failed. dir={}, catalog={}, error={}",
                downloadDir,
                catalogType.getCode(),
                e.getMessage());
    }
}
```

Другие методы `RetentionService` не менять. Он удаляет audit по сроку, но не удаляет XLSX-отчеты: их имена не совпадают с префиксами файлов реестров.

### 1.3. Конфиг RFM

Файл:

```text
rfm-downloader/config/config.template.json
```

Заменить объект `Retention` полностью:

```json
"Retention": {
  "Enabled": true,
  "KeepAuditDays": 60,
  "KeepDownloadedVersions": 0
}
```

В рабочем `rfm-downloader/config/config.json` также установить:

```json
"KeepDownloadedVersions": 0
```

`0` означает «хранить все версии». Чтобы включить лимит, указать положительное число, например `10`.

## 2. Всегда очищать завершенные events RFM

Файл:

```text
rfm-downloader/src/main/java/org/ikozmin/rfm/Main.java
```

Найти метод:

```java
private void applyRetentionIfNeeded(AppConfig config, Path workDir, Path downloadDir, List<CatalogType> catalogTypes) {
```

Заменить метод полностью:

```java
/**
 * Применяет retention к audit и реестрам при включенной настройке,
 * а завершенные events очищает всегда.
 */
private void applyRetentionIfNeeded(
        AppConfig config,
        Path workDir,
        Path downloadDir,
        List<CatalogType> catalogTypes
) {
    RetentionService retentionService = new RetentionService(config.getRetention());

    if (retentionService.isEnabled()) {
        for (CatalogType catalogType : catalogTypes) {
            retentionService.apply(workDir, downloadDir, catalogType);
        }
    } else {
        log.info("Registry and audit retention is disabled");
    }

    Path eventRootDir = Path.of(config.getEvents() == null
            ? "events/registry-updated"
            : config.getEvents().getDirectory()
    );

    new EventRetentionService().apply(eventRootDir);
}
```

Зачем: `Retention.Enabled=false` больше не приводит к вечному накоплению `processed`, `failed`, `results` в RFM. Папки `new`, `processing`, `retry` не затрагиваются.

## 3. Zenith events: код не менять

В `zenith-processor/src/main/java/org/ikozmin/zenith/ZenithProcessorMain.java` уже есть корректный метод:

```java
private void applyEventRetention(ZenithConfig config) {
    EventRetentionService retentionService = new EventRetentionService();

    retentionService.apply(Path.of(config.getEvents().getRegistryUpdatedDirectory()));

    for (String directory : config.getEvents().getImportCompletedDirectories()) {
        retentionService.apply(Path.of(directory));
    }

    String checkDirectory = config.getEvents().getCheckDirectory();

    if (checkDirectory != null && !checkDirectory.isBlank()) {
        retentionService.apply(Path.of(checkDirectory));
    }
}
```

Он очищает завершенные события всех доступных Zenith очередей. Повторный вызов для одинакового пути безопасен.

## 4. Отчеты Zenith: код не менять

XLSX-отчеты должны располагаться только в каталоге:

```text
Zenith.Reports.<catalog>.OutputDirectory
```

Пример:

```json
"OutputDirectory": "downloads/zenith-reports/te21"
```

Правильная структура:

```text
downloads/
  zenith-reports/
    te21/
    un/
    mvk/
  fes-packages/

events/
  registry-updated/
  zenith-imported/
```

Не размещать отчеты в `events/.../processed`, `failed` или `results`. `EventRetentionService` в `downloads` не заходит, поэтому XLSX и черновики ФЭС не удалит.

## 5. Логи: код не менять

В обоих файлах:

```text
rfm-downloader/src/main/resources/logback.xml
zenith-processor/src/main/resources/logback.xml
```

уже настроено:

```xml
<maxHistory>30</maxHistory>
<totalSizeCap>300MB</totalSizeCap>
```

Архивы логов автоматически удаляются после 30 дней. Активные `rfm-client.log` и `zenith-processor.log` не удаляются, пока используются процессом.

## 6. Проверка

```bat
mvn clean package
```

Проверить:

1. При `KeepDownloadedVersions: 0` после более чем 10 загрузок все версии реестра остаются на диске.
2. Тестовые файлы старше 30 дней из `processed`, `failed`, `results` удаляются после запуска RFM и Zenith.
3. Файлы из `new`, `processing`, `retry` не удаляются.
4. Старый XLSX в `downloads/zenith-reports/te21` остается после всех retention-процедур.
5. Архивы логов старше 30 дней удаляются Logback.
