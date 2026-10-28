# Поток

**Голосовые заметки для Android + часов Amazfit Active.**
Наговариваешь мысль → распознавание речи → LLM разбирает (тип, суть, время события, чек-лист) → заметка оседает в цепочке мыслей, которую можно связывать с другими и прогонять через собственные роли-обработчики.

<p align="center"><b>v3.4</b> · code 25 · minSdk 26 · targetSdk 34 · watch v0.4.0</p>

> **Репозиторий содержит оба проекта:** Android-приложение (`app/`) и приложение для часов Amazfit Active на Zepp OS (`watch/`). См. раздел [Часы (watch/)](#часы-watch) ниже.

---

## Возможности

**Голос → текст → разбор.** Запись с телефона или релеем с часов Amazfit Active. STT (любой OpenAI-совместимый провайдер) → LLM извлекает тип (`note` / `idea` / `task` / `reminder` / `list`), краткое название, время события и список дел.

**Чек-листы и ручные заметки.** LLM выделяет `type=list` → в экране заметки появляются реальные тапаемые пункты с чекбоксами (зачёркивание + сохранение в БД). Фолбэк: если LLM отдал список с пустыми пунктами — транскрипт сплитится на строки автоматически. Плюс кнопка «+» в шапке — вводишь текст вручную, он проходит тот же LLM-разбор (без STT).

**Связи и обработчики.** В экране заметки — «Связи» (поиск родственных заметок по пересечению слов/типа) и «Дополнить своим» (дописать текст к заметке). Плюс цветные облачка-обработчики.

**Свои обработчики-роли.** В Настройках → Обработчики создаёшь LLM-роли: название («Финаналитик») + рамки («ты серьёзный финансовый аналитик, оцени с точки зрения выгоды»). В заметке появляются цветные облачка — тапаешь, и мысль разбирается через эту оптику. 8 цветов на выбор.

**Самонастройка приложения.** Системная заметка «Оформление» → вводишь или наговариваешь «сделай акцент оранжевым», «пусть фон будет тёмный» — приложение мутирует само. Никаких слайдеров и пресетов, только фраза. Зелёный записи и красный стопа зарезервированы — их тема не трогает.

**Локальные напоминания.** Заметки с временем события (`is_event`) будят `AlarmManager` в момент `dueIso − remindOffset` и показывают свою нотификацию от «Поток». Без системного календаря, без разрешения на календарь. После перезагрузки телефона все будильники перепланируются автоматически.

**Часы Amazfit Active.** Релей: часы пишут Opus-аудио → `NanoServer` на телефоне принимает `/transcribe` и **мгновенно отвечает `{accepted}`** после декода Opus→WAV. STT+LLM идёт асинхронно в `ProcessingQueue`, транскрипт приезжает на часы через периодический SYNC. Часы не висят на облаке 2–5 минут — для 17 КБ записи: ~2с чанкинг + accept. Иконка-растр с круговой маской, навигация свайпом, статусы `queued → sending → pending (обработка…) → done/error`.

## Архитектура

- **Native Java + Activity**, без Jetpack Compose, без внешних библиотек кроме `concentus` (Opus-декодер для аудио с часов).
- **Raw `HttpURLConnection` + `org.json`** для STT/LLM — никаких Retrofit/Gson.
- **SQLite напрямую** (`NotesDbHelper`, `NotesStore`), без Room.
- **BYOK** — ключи и эндпоинты STT/LLM хранятся в SharedPreferences на устройстве, в исходниках их нет. Любой OpenAI-совместимый провайдер.

Чистая логика (парсинг LLM-ответа, фолбэк-сплит чек-листов, кодек обработчиков, форматтер времени напоминаний, парсер команд самонастройки) вынесена из Android-зависимостей и покрыта unit-тестами — **121 тест**.

## Сборка

```bash
# JDK 17, Android SDK 34
./gradlew assembleDebug          # APK
./gradlew :app:testDebugUnitTest # тесты
# → app/build/outputs/apk/debug/app-debug.apk
```

В Настройках приложения задай `llm_key`, `llm_base_url`, `llm_model` и STT-провайдера. По умолчанию LLM-эндпоинт `https://plusvibeapi.ru/v1`, модель `z-ai/glm-5.2`.

## Часы (watch/)

Приложение для Amazfit Active на Zepp OS 3.0 — исходники в `watch/`. Запись Opus-аудио → чанкинг base64 по BLE → side-service делает `fetch` на `NanoServer` телефона (`/transcribe`) → часы получают транскрипт через периодический SYNC.

```
watch/
├── page/index.js        # часы: список, запись, очередь, SYNC-мерж, экран детали
├── app-side/index.js    # side-service: аккумулятор чанков, fetch /transcribe
├── app-service/index.js # фоновый сервис
├── widget/index.js      # secondary-widget на циферблат
├── app.json             # appId, version 0.4.0 (code 40), target amazfit-active
├── app.js               # BaseApp (messaging-канал)
├── assets/              # icon.png + preview (растр, круговая маска)
└── package.json         # @zeppos/zml, @zeppos/device-types
```

### Сборка часов

```bash
cd watch
npm install              # первый раз: @zeppos/zml, @zeppos/device-types
npx zeus build           # → dist/<appId>-Поток-<ver>-<ts>.zab
# или для отладки: npx zeus preview -t "Amazfit Active"  # QR в Zepp App
```

> `zeus build` выходит с кодом 1 даже при успехе (legal notice про промежуточные продукты) — проверяй наличие `.zab` в `dist/`, а не exit code.

### Протокол v3.4 (часы ↔ телефон)

1. Часы режут base64 аудио на куски по 2048 символов → `STT_CHUNK` по BLE (await-ACK каждый).
2. На последнем куске side-service делает `POST /transcribe` с явным `id` в body.
3. Телефон декодирует Opus→WAV, сохраняет заметку `state=PENDING`, отвечает `{accepted:true, id}` **мгновенно**.
4. Часы ставят статус `pending` («обработка…»), файл НЕ удаляют (до терминала).
5. Телефон асинхронно: STT → LLM → `state=DONE` → broadcast.
6. Следующий SYNC: часы видят `status=done` → обновляют текст, удаляют аудиофайл. При `status=error` (retry ≥ 5) — показывают причину, файл оставляют для повторной отправки.

**Порядок выкатки:** часы первыми. Старый watch без ветки `{accepted}` уйдёт в бесконечный ресенд, если телефон уже обновлён.

## Структура

```
app/src/main/java/com/nexara/voicenotes/
├── MainActivity.java          # список заметок, запись, ручной ввод (+)
├── ThoughtDetailActivity.java # экран мысли: связи, чек-лист, облачка
├── NotesStore.java            # SQLite CRUD + statusForState() (проекция state)
├── ProcessingQueue.java       # очередь: claimForProcessing, STT+LLM, recoverStale
├── RelayService.java          # /transcribe → {accepted}, /sync, NanoServer
├── NanoServer.java            # HTTP-сервер для релея часов
├── LlmExtractor.java          # чистый парсер LLM-ответа (+ промпт, splitItems)
├── OpenAiLlmProvider.java     # OpenAI-совместимый LLM
├── Processor*.java            # модель/кодек/хранилище/экран обработчиков-ролей
├── AppCommandParser.java      # парсер фраз самонастройки
├── AppMutator.java            # применение мутаций темы
├── ReminderScheduler.java     # AlarmManager: локальные напоминания
├── ReminderReceiver.java      # BroadcastReceiver → нотификация
├── ReminderFmt.java           # форматтер времени («Сегодня 16:00»)
└── ThemeManager.java          # единый источник цвета + onColor()

watch/                          # приложение для Amazfit Active (Zepp OS 3.0)
├── page/index.js              # UI часов: список, запись, SYNC, детали
├── app-side/index.js          # BLE side-service → fetch /transcribe
└── app.json                   # appId 1131887, v0.4.0
```

## Лицензия

MIT — см. [LICENSE](LICENSE).
