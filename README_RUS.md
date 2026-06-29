# RFM Automation

RFM Automation - multi-module Java 21 проект для автоматизации работы с перечнями Росфинмониторинга и Zenith.

Проект рассчитан на регулярный запуск по расписанию, например один раз в час через Планировщик заданий Windows.

## Модули

| Модуль | Назначение |
| --- | --- |
| `common` | Общие JSON-утилиты, файловые события, summary Zenith и интерфейсы уведомлений. |
| `rfm-downloader` | Авторизация в Росфинмониторинге, проверка версий перечней, скачивание файлов, публикация события, запуск Zenith и отправка итогового уведомления. |
| `zenith-processor` | Обработка события, загрузка XML в Zenith, массовая проверка, скачивание отчета, анализ отчета и подготовка черновиков ФЭС. |
| `distribution` | Сборка итогового ZIP с jar-файлами, зависимостями, скриптами, конфигами и XML-фильтром Zenith. |

## Текущий сценарий работы

1. `rfm-downloader` авторизуется в API сервисного концентратора Росфинмониторинга.
2. Проверяет выбранный перечень по `idXml`.
3. Если появилась новая версия, скачивает архив или XML.
4. Для ZIP-перечней распаковывает XML.
5. Публикует событие `events/registry-updated/new/*.json`; в поле `registryFile` указывается XML, а не ZIP.
6. Запускает `zenith-processor`.
7. `zenith-processor` загружает XML в Zenith, запускает массовую проверку, формирует и скачивает отчет `Xlsx`.
8. Анализатор читает лист `Таблица_Проверок`, убирает дубли и сравнивает найденных лиц с локальной TSV-базой.
9. Для новых лиц готовятся черновики ФЭС в `downloads/fes-packages`.
10. `zenith-processor` пишет результат в `events/registry-updated/results/<eventId>-zenith-summary.json`.
11. `rfm-downloader` читает summary Zenith и отправляет одно общее уведомление.

Автоматическая отправка ФЭС в Росфинмониторинг сейчас не выполняется. Сотрудник вручную проверяет подготовленные черновики и принимает решение.

## Технологии

- Java 21
- Maven multi-module
- CryptoPro CSP/JCP/JTLS
- Java `HttpClient`
- Jackson
- SLF4J + Logback
- Jakarta Mail
- Telegram Bot API через `curl --resolve`
- Apache POI для чтения `Xlsx`-отчетов Zenith
- picocli
- JUnit 5, Mockito, AssertJ

## Поддерживаемые перечни

| Код | Описание |
| --- | --- |
| `te21` | Актуальный перечень террористов и экстремистов |
| `te2` | Старый метод перечня террористов и экстремистов |
| `mvk` | Перечень решений МВК |
| `un` | Перечень ООН |
| `un-rus` | Перечень ООН на русском языке |

По умолчанию используется `te21`.

## Структура установочного ZIP

Итоговый архив собирается модулем `distribution`:

```text
target/distr/rfm-automation-2.1.2.zip
```

Ожидаемая структура архива:

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

Рабочие папки создаются рядом со скриптами:

```text
downloads/
events/
data/
logs/
```

## Конфигурация

Основной конфиг РФМ:

```text
config/config.json
```

Конфиг Zenith:

```text
config/zenith-config.json
```

Важные пути в `zenith-config.json`:

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

Секреты можно передавать через переменные окружения, где это поддержано:

```text
RFM_USERNAME
RFM_PASSWORD
RFM_CERT_SERIAL
ZENITH_PASSWORD
```

Не храните в репозитории реальные пароли, закрытые ключи, скачанные перечни, логи и подготовленные ФЭС-файлы.

## Запуск

Полный штатный запуск:

```bat
run-rfm.bat
```

Ручной запуск только Zenith-части по уже опубликованному событию:

```bat
run-zenith-once.bat
```

Пример прямого запуска:

```bat
java -cp "rfm-downloader.jar;libs\*" org.ikozmin.rfm.Main --config config\config.json --prod --catalog te21
```

## События

События обновления перечня хранятся здесь:

```text
events/registry-updated/
  new/
  processing/
  processed/
  failed/
  results/
```

Поле `registryFile` в событии должно указывать на XML-файл, который загружается в Zenith. Для TE/MVK ZIP-файлов `rfm-downloader` сначала распаковывает XML и только потом публикует событие.

Результат Zenith сохраняется здесь:

```text
events/registry-updated/results/<eventId>-zenith-summary.json
```

## Обработка в Zenith

`zenith-processor` выполняет следующие этапы:

1. Загружает XML в `/zenith-object/api/v1/opercontrol/person_lists`.
2. Запускает массовую проверку ПОД/ФТ.
3. Формирует отчет с `outDocType=10217`.
4. Скачивает отчет в формате `Xlsx`.
5. Анализирует лист `Таблица_Проверок`.
6. Ищет совпадения по признаку списка террористов в `ЗЛ_РискОснования`.
7. Убирает повторы по ключу: нормализованное ФИО + номер счета.
8. Сравнивает найденных лиц с `data/zenith-found-persons.tsv`.
9. Для новых лиц готовит черновики ФЭС.
10. Сохраняет summary для итогового уведомления.

Локальная база найденных лиц пока остается TSV:

```text
data/zenith-found-persons.tsv
```

H2 сейчас не используется намеренно. Он понадобится позже, когда появятся ручные статусы согласования, отправка ФЭС, квитанции и история решений.

## Черновики ФЭС

Черновики сохраняются здесь:

```text
downloads/fes-packages/<дата-проверки>/<ключ-лица>/
```

В каждом каталоге сейчас создаются:

```text
FM03_DRAFT_<ключ-лица>.xml
FM03_DRAFT_<ключ-лица>.xml.sig
```

Файл `.sig` пока является заглушкой. Реальная detached-подпись CryptoPro и отправка через `formalized-message/send` относятся к следующим этапам.

Текущий рабочий статус:

```text
FOUND -> DRAFT_PREPARED -> REVIEW_REQUIRED
```

В будущем могут появиться:

```text
APPROVED -> SENT -> TICKET_RECEIVED
REJECTED
```

## Единое уведомление

Уведомление отправляется один раз после завершения всех включенных модулей.

В уведомление входят:

1. Блок РФМ: перечень, `idXml`, путь к XML, checksum.
2. Блок Zenith:
   - новых лиц не найдено; или
   - список новых лиц и пути к черновикам ФЭС.
3. Напоминание, что автоматическая отправка в Росфинмониторинг не выполнялась.

Контракты уведомлений находятся в `common`:

```text
org.ikozmin.common.notification.NotificationMessage
org.ikozmin.common.notification.NotificationSender
```

Реализации находятся в `rfm-downloader`:

```text
EmailNotificationService
TelegramNotificationService
NotificationService
UnifiedNotificationTextBuilder
```

Для Telegram используется `curl.exe --resolve api.telegram.org:443:<ApiIp>`, чтобы HTTPS-сертификат проверялся по имени `api.telegram.org`, но подключение шло к заданному IP.

## Текущее ограничение

После загрузки перечня в Zenith API возвращает HTTP 200 без тела ответа. Проект пока не разбирает лог Zenith и не показывает, сколько записей было добавлено, изменено или удалено при импорте. Следующим улучшением стоит проверить endpoint Zenith log/export и использовать его, если он возвращает надежные счетчики.

## Сборка

Из корня Maven-проекта:

```bat
mvn -q clean package
```

Корень проекта:

```text
G:\tmp\fedfsm\java
```

## Эксплуатационные заметки

- Запускайте только один экземпляр одновременно.
- Рекомендуемый режим: один раз в час.
- Папки `config/`, `downloads/`, `events/`, `data/`, `logs/` должны находиться на постоянном диске.
- Если Zenith не обработал событие, проверьте `events/registry-updated/failed`.
- Результат Zenith можно проверить в `events/registry-updated/results`.
- Черновики ФЭС нужно проверять вручную перед любым будущим этапом отправки.
