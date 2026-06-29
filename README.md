# RFM Automation

RFM Automation is a Java 21 multi-module command-line project for downloading Rosfinmonitoring registry updates, importing the updated terrorist/extremist registry into Zenith, checking Zenith results, preparing draft FES files, and sending one unified notification after all enabled processing stages are complete.

The project is intended for scheduled execution, for example once per hour from Windows Task Scheduler.

## Modules

| Module | Purpose |
| --- | --- |
| `common` | Shared JSON, file event, Zenith summary, and notification contracts. |
| `rfm-downloader` | Authenticates in Rosfinmonitoring, checks registry versions, downloads files, publishes update events, runs Zenith, and sends the final notification. |
| `zenith-processor` | Consumes registry update events, imports XML into Zenith, runs mass check, downloads the report, analyzes it, and prepares draft FES packages. |
| `distribution` | Builds the final ZIP package with jars, dependencies, scripts, configs, and Zenith filter files. |

## Current Flow

1. `rfm-downloader` authenticates in the Rosfinmonitoring Service Concentrator API.
2. It checks the selected registry by `idXml`.
3. If a new version exists, it downloads the registry archive or XML.
4. For ZIP registries, it extracts the XML file.
5. It publishes `events/registry-updated/new/*.json`; the event points to the extracted XML, not to the ZIP archive.
6. It runs `zenith-processor`.
7. `zenith-processor` imports the XML into Zenith, runs mass check, creates and downloads the `Xlsx` report.
8. The report analyzer reads `Таблица_Проверок`, deduplicates rows by person key, and compares the result with the local TSV database.
9. For new persons, draft FES files are created under `downloads/fes-packages`.
10. `zenith-processor` writes `events/registry-updated/results/<eventId>-zenith-summary.json`.
11. `rfm-downloader` reads the Zenith summary and sends one unified notification through enabled channels.

Automatic FES sending to Rosfinmonitoring is not implemented. A human employee reviews the prepared drafts and decides what to do next.

## Technology Stack

- Java 21
- Maven multi-module build
- CryptoPro CSP/JCP/JTLS
- Java `HttpClient`
- Jackson
- SLF4J + Logback
- Jakarta Mail
- Telegram Bot API through `curl --resolve`
- Apache POI for Zenith `Xlsx` report parsing
- picocli
- JUnit 5, Mockito, AssertJ

## Supported Registries

| Code | Description |
| --- | --- |
| `te21` | Current terrorists and extremists registry |
| `te2` | Legacy terrorists and extremists registry |
| `mvk` | MVK freeze registry |
| `un` | UN registry |
| `un-rus` | UN registry, Russian version |

Default catalog: `te21`.

## Distribution Layout

The final ZIP is built by the `distribution` module:

```text
target/distr/rfm-automation-2.1.2.zip
```

Expected layout inside the ZIP:

```text
rfm-downloader.jar
zenith-processor.jar
libs/
config/
  config.json
  zenith-config.json
  zenith/
    podft-report-filter.xml
run-rfm.bat
run-zenith-once.bat
```

Runtime directories are created near the scripts:

```text
downloads/
events/
data/
logs/
```

## Configuration

Main RFM config:

```text
config/config.json
```

Zenith config:

```text
config/zenith-config.json
```

Important paths:

```json
{
  "Events": {
    "Directory": "events/registry-updated"
  },
  "Results": {
    "Directory": "events/registry-updated/results"
  },
  "Zenith": {
    "Fes": {
      "OutputDirectory": "downloads/fes-packages"
    },
    "Report": {
      "FilterTemplatePath": "config/zenith/podft-report-filter.xml",
      "OutputDirectory": "downloads/zenith-reports"
    }
  }
}
```

Secrets can also be supplied by environment variables where supported:

```text
RFM_USERNAME
RFM_PASSWORD
RFM_CERT_SERIAL
ZENITH_PASSWORD
```

Do not commit real credentials, private keys, downloaded registries, logs, or produced FES drafts.

## Run

Normal full run:

```bat
run-rfm.bat
```

Manual Zenith-only run for already published events:

```bat
run-zenith-once.bat
```

Direct command example:

```bat
java -cp "rfm-downloader.jar;libs\*" org.ikozmin.rfm.Main --config config\config.json --prod --catalog te21
```

## Events

Registry update events are stored in:

```text
events/registry-updated/
  new/
  processing/
  processed/
  failed/
  results/
```

The event field `registryFile` must point to the XML file used by Zenith. For TE/MVK ZIP downloads, `rfm-downloader` extracts the XML and publishes the XML path in the event.

Zenith writes the result summary to:

```text
events/registry-updated/results/<eventId>-zenith-summary.json
```

## Zenith Processing

The Zenith processor performs these stages:

1. Import registry XML to `/zenith-object/api/v1/opercontrol/person_lists`.
2. Run AML/CFT mass check.
3. Create report with `outDocType=10217`.
4. Download report as `Xlsx`.
5. Analyze the `Таблица_Проверок` sheet.
6. Detect terrorist-list matches by `ЗЛ_РискОснования`.
7. Deduplicate matches by normalized person name and account number.
8. Compare matches with `data/zenith-found-persons.tsv`.
9. Prepare draft FES packages for new persons.
10. Save a summary JSON for the final notification.

The local person database remains TSV for now:

```text
data/zenith-found-persons.tsv
```

H2 is intentionally not used yet. It becomes useful later when manual approval statuses, FES sending, tickets, and history need stronger querying and migrations.

## Draft FES Packages

Drafts are stored under:

```text
downloads/fes-packages/<check-date>/<person-key>/
```

Each package currently contains:

```text
FM03_DRAFT_<person-key>.xml
FM03_DRAFT_<person-key>.xml.sig
```

The `.sig` file is a placeholder. Real detached CryptoPro signing and `formalized-message/send` are future stages.

Current human workflow:

```text
FOUND -> DRAFT_PREPARED -> REVIEW_REQUIRED
```

Future workflow may add:

```text
APPROVED -> SENT -> TICKET_RECEIVED
REJECTED
```

## Unified Notifications

Notifications are sent once, after all enabled modules finish.

The notification contains:

1. RFM download section: catalog, idXml, XML path, checksum.
2. Zenith section:
   - "No new persons found", or
   - list of new persons and draft FES package directories.
3. Reminder that automatic sending to Rosfinmonitoring was not performed.

Notification contracts live in `common`:

```text
org.ikozmin.common.notification.NotificationMessage
org.ikozmin.common.notification.NotificationSender
```

Implementations live in `rfm-downloader`:

```text
EmailNotificationService
TelegramNotificationService
NotificationService
UnifiedNotificationTextBuilder
```

Telegram uses `curl.exe --resolve api.telegram.org:443:<ApiIp>` so HTTPS still validates against `api.telegram.org` while connecting to a configured IP.

## Known Limitation

Zenith import currently returns HTTP 200 with an empty body. The project does not yet parse Zenith operation logs to determine how many records were added, changed, or removed during import. The next improvement should use the Zenith log/export endpoint if its returned text contains reliable counters.

## Build

From the Maven project root:

```bat
mvn -q clean package
```

Root directory:

```text
G:\tmp\fedfsm\java
```

## Operational Notes

- Run one instance at a time.
- Prefer hourly scheduling.
- Keep `config/`, `downloads/`, `events/`, `data/`, and `logs/` on persistent storage.
- Check `events/registry-updated/failed` if Zenith processing fails.
- Check `events/registry-updated/results` to inspect what Zenith reported.
- Review draft FES files manually before any future sending step.
