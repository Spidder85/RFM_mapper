# RFM Client

RFM Client is a Java command-line application for downloading Rosfinmonitoring registry updates through the Service Concentrator API.

The application is designed to run by schedule, for example once per hour from Windows Task Scheduler.

## Features

- Authenticates in the Rosfinmonitoring API with a client certificate.
- Checks whether the selected registry has a new `idXml`.
- Downloads a registry file only when a new version is available.
- Saves files atomically through a temporary `.part` file.
- Validates downloaded ZIP/XML files.
- Extracts ZIP registries after download.
- Calculates SHA-256 checksum.
- Stores local state to avoid duplicate downloads.
- Saves audit JSON envelopes for access and diagnostics.
- Sends optional email and Telegram notifications.
- Cleans old audit files and old downloaded versions by retention settings.

## Supported Registries

| Code | Description |
| --- | --- |
| `te21` | Terrorists and extremists registry, current production version |
| `te2` | Legacy terrorists and extremists registry |
| `mvk` | MVK freeze registry |
| `un` | UN registry |
| `un-rus` | UN registry, Russian version |

Default catalog: `te21`.

## Technology Stack

- Java 21
- Maven
- CryptoPro CSP/JCP/JTLS
- Jackson
- SLF4J + Logback
- Jakarta Mail
- Telegram Bot API
- picocli
- JUnit 5, Mockito, AssertJ

The project is intentionally not a web service. It is a compact scheduled utility.

## Requirements

- Java 21 runtime.
- Maven for build.
- CryptoPro CSP/JCP installed and configured.
- Client certificate with private key.
- Access to required Rosfinmonitoring API methods.
- `curl` available in `PATH` when Telegram notifications are enabled.

API base URL:

```text
https://portal.fedsfm.ru:8081/Services/fedsfm-service
```

Telegram notifications use `curl --resolve` because `api.telegram.org` may be blocked or unavailable through normal DNS in some environments.

## Build

Run from the `java` directory:

```powershell
mvn clean package
```

Build output:

```text
target/rfm-client.jar
target/libs/
target/distr/rfm-client-2.0.0.zip
```

## Configuration

Create a working config:

```text
config.template.json -> config.json
```

Minimal configuration:

```json
{
  "Credentials": {
    "UserName": "YOUR_RFM_USERNAME",
    "Password": "YOUR_RFM_PASSWORD"
  },
  "Certificate": {
    "SerialNumber": "YOUR_CERTIFICATE_SERIAL_NUMBER",
    "UseCryptoPro": true
  },
  "DefaultCatalog": "te21",
  "UseTestContour": false,
  "OutputDirectory": "downloads",
  "Notifications": {
    "Enabled": false
  }
}
```

Secrets can also be provided through environment variables:

```text
RFM_USERNAME
RFM_PASSWORD
RFM_CERT_SERIAL
```

Do not commit real credentials, private keys, downloaded registries, or logs.

## CryptoPro Defaults

The normal config does not need to include the full CryptoPro section. The application uses these defaults internally:

```text
Provider classes: JCSP, JCP, Crypto, JTLS
Key store: REGISTRY / JCSP
Key manager: GostX509 / JTLS
Trust store: Windows-ROOT / SunMSCAPI
Trust manager: PKIX
SSL: GostTLSv1.2 / JTLS
```

If a specific machine requires custom settings, add the optional `Certificate.CryptoPro` section to `config.json`.

## Run

Production run:

```powershell
java -cp "target/rfm-client.jar;target/libs/*" org.ikozmin.rfm.Main --config config.json --prod --catalog te21
```

Test contour:

```powershell
java -cp "target/rfm-client.jar;target/libs/*" org.ikozmin.rfm.Main --config config.json --test --catalog te2
```

Help:

```powershell
java -cp "target/rfm-client.jar;target/libs/*" org.ikozmin.rfm.Main --help
```

Options:

```text
-c, --config <path>    path to config.json
-k, --catalog <code>   te2, te21, mvk, un, un-rus
    --prod             production contour
    --test             test contour
    --contour <value>  prod or test
```

The registry download directory is configured through `OutputDirectory` in `config.json`.

## Output

Working directory:

```text
downloads/
  state.properties
  audit/
    *.json
```

Downloaded registry files are stored in `OutputDirectory`. Files are grouped by registry date folder.

Example:

```text
downloads/
  260622/
    suspect_20260622_<id>.zip
    *.xml
```

`state.properties` stores the last known registry id, downloaded file path, download time, and checksum.

## Notifications

Notifications are disabled by default.

Email and Telegram can be enabled independently:

```json
"Notifications": {
  "Enabled": true,
  "Email": {
    "Enabled": true,
    "SmtpHost": "smtp.your-company.ru",
    "SmtpPort": 25,
    "SmtpUsername": "",
    "SmtpPassword": "",
    "UseTls": false,
    "From": "noreply@your-company.ru",
    "To": ["admin@your-company.ru"],
    "Subject": "Обновлен перечень Росфинмониторинга",
    "IncludeAttachment": false,
    "IncludeFileChecksum": true
  },
  "Telegram": {
    "Enabled": true,
    "Token": "YOUR_TELEGRAM_BOT_TOKEN",
    "ChatIds": ["YOUR_TELEGRAM_CHAT_ID"],
    "ApiIp": "149.154.167.220",
    "IncludeFileChecksum": true
  }
}
```

Notifications are sent only when a new file is downloaded.

Telegram token is masked in logs. The request is sent through `curl --resolve api.telegram.org:443:<ApiIp>` to keep the HTTPS host name valid while connecting to a fixed Telegram IP.

## Retention

Retention is configured in `config.json`:

```json
"Retention": {
  "Enabled": true,
  "KeepAuditDays": 30,
  "KeepDownloadedVersions": 10
}
```

Retention removes old audit files and old downloaded registry versions for the current catalog.

## Scheduled Execution

Recommended scheduler setup:

- Run once per hour.
- Use a stable working directory.
- Keep `config.json`, `downloads`, and `logs` outside temporary folders.
- Do not run multiple instances at the same time.
- Monitor exit code and `logs/rfm-client.log`.

## Access Procedure

To request production access:

1. Request access to test methods in the Rosfinmonitoring personal account.
2. Run the required test methods.
3. Save the JSON envelopes.
4. Fill out the production access request form.
5. Pack the form and envelopes into a ZIP archive.
6. Send the archive through support.

The `authenticate` envelope is not required.

## Troubleshooting

### TLS errors

Check certificate serial, private key availability, CryptoPro installation, and port `8081` in the API URL.

### Telegram does not send

Check `curl`, bot token, chat id, and `ApiIp`.

### Email does not send

Check SMTP host, port, authentication, TLS setting, sender permissions, and firewall rules.
