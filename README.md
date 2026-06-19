# RFM Client

RFM Client is a command-line Java application for working with the Rosfinmonitoring Service Concentrator API.

The application checks whether a registry has been updated, downloads the current file when a new version is available, validates the downloaded file, saves local state, and can notify users by email and Telegram.

## What The Application Does

- Authenticates in the Rosfinmonitoring API with a client certificate.
- Checks the selected registry catalog.
- Compares the remote `idXml` with the locally saved state.
- Downloads the registry file only when a new version is available.
- Saves the file atomically through a temporary `.part` file.
- Validates downloaded ZIP/XML files.
- Calculates SHA-256 checksum.
- Stores local update state.
- Writes audit JSON envelopes for API request/response confirmation.
- Optionally sends update notifications by email and Telegram.

## Supported Registries

| Code | Registry |
| --- | --- |
| `te21` | Terrorists and extremists registry, current production version |
| `te2` | Legacy terrorists and extremists registry |
| `mvk` | MVK freeze registry |
| `un` | UN registry |
| `un-rus` | UN registry, Russian version |

Default catalog: `te21`.

## Technology Stack

- Java 21 runtime environment.
- Maven for build and distribution packaging.
- Java HTTP Client and HTTPS connection APIs.
- CryptoPro CSP/JCP/JTLS for GOST TLS and client certificate authentication.
- Jackson for JSON serialization and configuration loading.
- SLF4J + Logback for logging.
- Jakarta Mail for email notifications.
- Telegram Bot API for Telegram notifications.
- picocli for command-line argument parsing.
- JUnit 5, Mockito, AssertJ for tests.

The project is intentionally implemented as a small scheduled command-line utility, not as a web service. This keeps deployment and hourly execution simple.

## Requirements

- Java 21 installed on the execution machine.
- Maven for building the project.
- CryptoPro CSP/JCP installed and configured.
- Client certificate with an accessible private key.
- Access to the required Rosfinmonitoring Service Concentrator methods.
- Network access to:

```text
https://portal.fedsfm.ru:8081/Services/fedsfm-service
```

For Telegram notifications, the machine must also have access to:

```text
https://api.telegram.org
```

## Project Structure

```text
src/main/java/org/ikozmin/rfm/
  audit/      API audit envelope files
  cert/       certificate loading and selection
  client/     Rosfinmonitoring API client, endpoints, retry, TLS client factory
  config/     application configuration models and loader
  crypto/     CryptoPro provider registration
  logging/    masking helpers
  model/      API and domain models
  service/    registry update logic, validation, notifications
  storage/    local state and checksum helpers
```

## Build

Run from the `java` directory:

```powershell
mvn clean package
```

The build produces:

```text
target/rfm-client.jar
target/libs/
target/distr/rfm-client-1.0.0.zip
```

The application uses a thin jar plus dependencies from `libs`.

## Configuration

Create a working config from the template:

```text
config.template.json -> config.json
```

Minimum configuration:

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

Do not commit real `config.json`, private keys, downloaded registry files, or logs.

## CryptoPro Settings

The template contains an extended `CryptoPro` section for environments where explicit provider configuration is required.

Typical values:

```json
"CryptoPro": {
  "ProviderClasses": [
    "ru.CryptoPro.JCSP.JCSP",
    "ru.CryptoPro.JCP.JCP",
    "ru.CryptoPro.Crypto.CryptoProvider",
    "ru.CryptoPro.ssl.Provider"
  ],
  "KeyStoreType": "REGISTRY",
  "KeyStoreProvider": "JCSP",
  "TrustStoreType": "WINDOWS-ROOT",
  "TrustStoreProvider": "SunMSCAPI",
  "SslProtocol": "GostTLSv1.2",
  "SslProvider": "JTLS"
}
```

For most installations these values should not be changed after the connection is confirmed working.

## Run

Run production registry check:

```powershell
java -cp "target/rfm-client.jar;target/libs/*" org.ikozmin.rfm.Main --config config.json --prod --catalog te21 --out downloads
```

Run test contour:

```powershell
java -cp "target/rfm-client.jar;target/libs/*" org.ikozmin.rfm.Main --config config.json --test --catalog te2 --out downloads-test
```

Show command-line help:

```powershell
java -cp "target/rfm-client.jar;target/libs/*" org.ikozmin.rfm.Main --help
```

Available options:

```text
-c, --config <path>    path to config.json
-o, --out <dir>        output directory
-k, --catalog <code>   te2, te21, mvk, un, un-rus
    --prod             production contour
    --test             test contour
    --contour <value>  prod or test
```

## Windows Script

`run.bat` is intended for the packaged distribution where these files are in one directory:

```text
rfm-client.jar
libs/
config.json
run.bat
```

The script starts the production `te21` check and writes output to `downloads`.

## Output

Typical output directory:

```text
downloads/
  state.properties
  audit/
    1_RespTE.json
    2_ReqTE.json
  te21/
    suspect_<date>_<id>.zip
```

`state.properties` stores the last known registry id, downloaded file path, download time, and SHA-256 checksum. It is used to avoid downloading the same registry version repeatedly.

Audit files are useful for diagnostics and for Rosfinmonitoring access procedures.

## Notifications

Notifications are optional and disabled when the `Notifications` block is absent or `Enabled` is `false`.

Email and Telegram can be enabled independently.

Email example:

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
    "To": [
      "admin@your-company.ru"
    ],
    "Subject": "RFM registry updated",
    "IncludeAttachment": false,
    "IncludeFileChecksum": true
  },
  "Telegram": {
    "Enabled": false
  }
}
```

Telegram example:

```json
"Notifications": {
  "Enabled": true,
  "Email": {
    "Enabled": false
  },
  "Telegram": {
    "Enabled": true,
    "Token": "YOUR_TELEGRAM_BOT_TOKEN",
    "ChatIds": [
      "YOUR_TELEGRAM_CHAT_ID"
    ],
    "IncludeFileChecksum": true
  }
}
```

Notifications are sent only when a new registry file is actually downloaded. If the registry is already current, no update notification is sent.

## Hourly Execution

The application is designed to be launched by a scheduler, for example Windows Task Scheduler.

Recommended scheduler behavior:

- Run once per hour.
- Use a stable working directory.
- Keep `config.json`, `downloads`, and `logs` outside temporary folders.
- Do not start a new instance if the previous run is still active.
- Monitor process exit code and log file.

## Access Procedure

To receive access to production API methods:

1. Request access to test methods through the Rosfinmonitoring Personal Account support form.
2. Run the required test methods.
3. Save the JSON request/response envelopes.
4. Prepare the production access request form.
5. Pack the form and required JSON envelopes into a ZIP archive.
6. Send the archive through the support request for production access.

The authentication request/response envelope is not required.

## Logging

Logs are written to:

```text
logs/rfm-client.log
```

Log rotation is configured by date and file size. Sensitive values such as token, certificate serial, and identifiers are masked in logs where possible.

## Exit Codes

The application maps common failure categories to exit codes through `ExitCode`:

- configuration errors
- certificate/TLS errors
- authentication errors
- API errors
- general application errors

This allows schedulers and monitoring tools to distinguish failed runs from successful runs with no updates.

## Troubleshooting

### TLS Errors

Check:

- API URL contains port `8081`;
- CryptoPro providers are installed;
- certificate serial is correct;
- certificate has a private key;
- certificate is the one bound to the Rosfinmonitoring account;
- `SslProtocol` and `SslProvider` match the working environment.

### No Production Access

If TLS succeeds but API returns authorization or access errors, complete the test method procedure and request production access through the Personal Account.

### Telegram Does Not Send

Check:

- bot token;
- chat id;
- network access to `api.telegram.org`;
- that the user or group has started/allowed the bot.

### Email Does Not Send

Check:

- SMTP host and port;
- whether authentication is required;
- TLS setting;
- sender address permissions;
- firewall rules from the execution machine to the SMTP server.

