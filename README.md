# RFM Automation

RFM Automation is a Java 21 command-line system for receiving Rosfinmonitoring registry updates, processing them in Zenith, preparing draft FES packages, and delivering operational notifications.

It is designed for unattended Windows Task Scheduler runs, normally once per hour. The system does not automatically send FES to Rosfinmonitoring: an employee reviews the draft and makes the final decision.

# Multilanguage README

[![en](https://img.shields.io/badge/lang-en-red.svg)](./README.md)
[![ru](https://img.shields.io/badge/lang-ru-green.svg)](./README_RUS.md)

## What It Does

- Downloads current Rosfinmonitoring registries through the Service Concentrator API.
- Supports terrorists/extremists, UN and MVK registries.
- Uses a client certificate, including CryptoPro CSP/JCP/JTLS installations.
- Publishes file-based events between servers.
- Imports updated registries into Zenith.
- Runs Zenith AML/CFT mass checks, downloads XLSX reports and detects new matches.
- Creates draft FES files for new matches.
- Sends clear email and Telegram notifications.
- Retries temporary Zenith failures and preserves permanent failures for investigation.

## Architecture

The recommended production deployment uses three independent scheduled stages:

```text
Server 1                         Server 2                         Server 3
--------                         --------                         --------
rfm-downloader                   zenith-processor                 zenith-processor
download only                    IMPORT_ONLY                       CHECK_ONLY

RegistryUpdated event  ------->  import to Zenith  ------------->  mass check
                                 ImportCompleted event             report / analysis / FES drafts
                                 import notification               check notification
```

`FULL` mode remains available for a single-server deployment. In the three-server scenario, Server 3 must use `CHECK_ONLY`, otherwise it would import the registry a second time.

## Modules

| Module | Responsibility |
| --- | --- |
| `common` | Shared JSON support, event queues, retry/retention logic, processing summaries and notification contracts. |
| `rfm-downloader` | Rosfinmonitoring authentication, registry version check, download, extraction, audit and `RegistryUpdated` publication. |
| `zenith-processor` | Zenith import, mass check, report download/analysis, draft FES preparation and notifications. |
| `distribution` | Assembles the deployable ZIP package with applications, dependencies, scripts and runtime configuration. |

## Technology Stack

- Java 21
- Maven multi-module build
- picocli command-line interface
- Java `HttpClient`
- Jackson for JSON and Java time types
- SLF4J with Logback
- Apache POI for Zenith XLSX reports
- Jakarta Mail for SMTP notifications
- Telegram Bot API through `curl.exe --resolve` for networks where the standard Telegram endpoint is unavailable
- CryptoPro CSP, JCP and JTLS for client-certificate TLS
- JUnit 5, Mockito and AssertJ for automated tests

## Supported Registries

| Code | Registry |
| --- | --- |
| `te21` | Current terrorists and extremists registry |
| `te2` | Legacy terrorists and extremists registry |
| `mvk` | MVK decisions registry |
| `un` | UN registry |
| `un-rus` | Russian-language UN registry |

## Processing Modes

| Mode | Purpose |
| --- | --- |
| `FULL` | Import, publish office events, run check, download report and prepare FES drafts. |
| `IMPORT_ONLY` | Import a new registry into Zenith and publish `ZenithImportCompleted` events for checking servers. |
| `CHECK_ONLY` | Consume `ZenithImportCompleted`, run the check, download/analyze the report and prepare FES drafts. |

The processor supports `--once`, `--drain` and `--watch`. For scheduled servers, use `--drain` so all currently available events are processed as one batch.

## Events and Retry

Each queue has the following lifecycle:

```text
new -> processing -> processed
                  -> retry  -> new
                  -> failed
```

- Temporary connectivity errors, timeouts and retryable Zenith responses are moved to `retry` and retried later.
- Invalid or non-retryable events are moved to `failed`.
- Before processing, the queue keeps only the latest pending event for each registry. Older pending events of the same registry are removed.
- Completed event files and summaries are retained for 30 days. Pending and retry events are not deleted automatically.

## Notifications

Notifications are optional and disabled by default.

- `IMPORT_ONLY` sends one summary about registries successfully uploaded to Zenith.
- `CHECK_ONLY` and `FULL` send one summary about check results, found persons and draft FES packages.
- `Email.ImportTo` is optional. When configured, it receives import notifications; otherwise the normal `Email.To` list is used.
- Telegram uses the configured `ChatIds` for every enabled Zenith notification.
- When RFM launches Zenith itself, Zenith notifications can be suppressed so only one combined notification is delivered.

## Important Configuration

Runtime configuration is stored near the deployed scripts:

```text
config/config.json
config/zenith-config.json
```

Do not commit real passwords, certificate identifiers, tokens, downloaded files, reports, logs or draft FES packages.

Environment variables supported by the application include:

```text
RFM_USERNAME
RFM_PASSWORD
RFM_CERT_SERIAL
ZENITH_PASSWORD
```

For `Zenith.BaseUrl`, specify the server base URL only, for example:

```json
"BaseUrl": "https://zenith-server"
```

The application adds `/zenith-object/api/...` itself.

## Storage and Retention

```text
downloads/zenith-reports/   Zenith XLSX reports, retained indefinitely
downloads/fes-packages/     Draft FES packages, retained indefinitely
events/.../processed/       Completed events, retained for 30 days
events/.../failed/          Failed events, retained for 30 days
events/.../results/         Processing summaries, retained for 30 days
logs/                       Logback archives, retained for 30 days
```

Set `Retention.KeepDownloadedVersions` to `0` to retain downloaded Rosfinmonitoring registries indefinitely. A positive value enables a version limit. Audit retention is controlled separately by `Retention.KeepAuditDays`.

## Build and Distribution

Build all modules from the project root:

```bat
mvn clean package
```

The distribution ZIP is created under:

```text
target/distr/rfm-automation-<version>.zip
```

The package contains application JARs, dependencies, operational scripts and working configuration files.

## Operational Scripts

| Script | Purpose |
| --- | --- |
| `run-rfm.bat` | Run RFM download stage. |
| `run-zenith-once.bat` | Process one Zenith event. |
| `run-zenith-drain.bat` | Process all available Zenith events. |
| `run-zenith-watch.bat` | Continuously poll a Zenith queue. |

Example scheduled commands:

```bat
run-rfm.bat
run-zenith-drain.bat --mode IMPORT_ONLY
run-zenith-drain.bat --mode CHECK_ONLY
```

## Operational Notes

- Run only one instance of each stage against the same queue at a time.
- Grant the scheduled-service accounts read/write access to the relevant network event directories.
- Keep `config`, `downloads`, `events`, `data` and `logs` on persistent storage.
- Review `failed` events and Zenith logs when an event does not complete.
- Review every generated FES draft before any future delivery to Rosfinmonitoring.
