# RFM Client

RFM Client - консольное Java-приложение для загрузки обновлений перечней Росфинмониторинга через API электронного сервиса "Сервисный концентратор".

Программа рассчитана на запуск по расписанию, например один раз в час через Планировщик заданий Windows.

## Возможности

- Авторизация в API Росфинмониторинга с клиентским сертификатом.
- Проверка выбранного перечня по `idXml`.
- Скачивание файла только при появлении новой версии.
- Безопасное сохранение через временный `.part` файл.
- Проверка скачанных ZIP/XML файлов.
- Распаковка ZIP-файлов после загрузки.
- Расчет SHA-256.
- Сохранение локального состояния.
- Сохранение audit JSON-файлов для диагностики и процедур доступа.
- Опциональные уведомления по email и Telegram.
- Очистка старых audit-файлов и старых версий по retention-настройкам.

## Поддерживаемые перечни

| Код | Описание |
| --- | --- |
| `te21` | Актуальный перечень террористов и экстремистов |
| `te2` | Старый метод перечня террористов и экстремистов |
| `mvk` | Перечень решений МВК о замораживании денежных средств |
| `un` | Перечень ООН |
| `un-rus` | Перечень ООН на русском языке |

По умолчанию используется `te21`.

## Технологии

- Java 21
- Maven
- CryptoPro CSP/JCP/JTLS
- Jackson
- SLF4J + Logback
- Jakarta Mail
- Telegram Bot API
- picocli
- JUnit 5, Mockito, AssertJ

Это не web-сервис, а небольшая утилита для регулярного запуска.

## Требования

- Java 21.
- Maven для сборки.
- Установленный и настроенный CryptoPro CSP/JCP.
- Клиентский сертификат с доступным закрытым ключом.
- Доступ к нужным методам API Росфинмониторинга.
- `curl` в `PATH`, если включены Telegram-уведомления.

Базовый адрес API:

```text
https://portal.fedsfm.ru:8081/Services/fedsfm-service
```

Для Telegram используется `curl --resolve`, потому что `api.telegram.org` может быть недоступен через обычный DNS.

## Сборка

Из папки `java`:

```powershell
mvn clean package
```

Результат сборки:

```text
target/rfm-client.jar
target/libs/
target/distr/rfm-client-2.0.0.zip
```

## Настройка

Создайте рабочий конфиг:

```text
config.template.json -> config.json
```

Минимальный пример:

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

Секреты можно передавать через переменные окружения:

```text
RFM_USERNAME
RFM_PASSWORD
RFM_CERT_SERIAL
```

Не храните реальные пароли, закрытые ключи, скачанные файлы и логи в репозитории.

## Запуск

Продуктивный контур:

```powershell
java -cp "target/rfm-client.jar;target/libs/*" org.ikozmin.rfm.Main --config config.json --prod --catalog te21
```

Тестовый контур:

```powershell
java -cp "target/rfm-client.jar;target/libs/*" org.ikozmin.rfm.Main --config config.json --test --catalog te2
```

Справка:

```powershell
java -cp "target/rfm-client.jar;target/libs/*" org.ikozmin.rfm.Main --help
```

Каталог для скачанных файлов задается в `config.json` через `OutputDirectory`.

## Уведомления

Уведомления по умолчанию выключены.

Email и Telegram включаются отдельно:

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

Уведомление отправляется только если скачана новая версия файла.

Telegram-токен маскируется в логах. Для подключения к Telegram используется `curl --resolve api.telegram.org:443:<ApiIp>`, чтобы сохранить корректное HTTPS-имя узла при подключении к конкретному IP.

## Retention

Настройки очистки:

```json
"Retention": {
  "Enabled": true,
  "KeepAuditDays": 30,
  "KeepDownloadedVersions": 10
}
```

Очистка удаляет старые audit-файлы и старые версии скачанных файлов для текущего перечня.

## Результаты работы

Рабочая папка:

```text
downloads/
  state.properties
  audit/
    *.json
```

Скачанные файлы сохраняются в `OutputDirectory`, внутри папки с датой перечня.

Пример:

```text
downloads/
  260622/
    suspect_20260622_<id>.zip
    *.xml
```

`state.properties` хранит последний известный `idXml`, путь к файлу, дату загрузки и checksum.

## Эксплуатация по расписанию

Рекомендуется:

- запускать один раз в час;
- использовать постоянную рабочую папку;
- не запускать несколько экземпляров одновременно;
- контролировать exit code;
- проверять `logs/rfm-client.log`.

## Получение продуктивного доступа

Общий порядок:

1. Запросить доступ к тестовым методам через Личный кабинет.
2. Выполнить тестовые методы.
3. Сохранить JSON-конверты.
4. Заполнить заявку на продуктивный доступ.
5. Упаковать заявку и JSON-конверты в ZIP.
6. Отправить архив в поддержку.

Конверт метода `authenticate` прикладывать не требуется.
