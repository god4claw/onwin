# OnWin — установка и использование

## Готовый APK

Последнюю сборку можно скачать со страницы релизов:

**[github.com/god4claw/onwin/releases](https://github.com/god4claw/onwin/releases)**

APK включает модели распознавания и работает офлайн сразу после установки.

```powershell
adb install -r onwin.apk
```

LLM-функции (резюме, имена спикеров, протокол) требуют отдельной модели — см.
[«Модель LLM»](#модель-llm-опционально).

## Сборка из исходников

### Требования

| Компонент | Значение |
|---|---|
| JDK | 17 |
| Android SDK | 37 |
| Устройство | arm64-v8a, Android 10+ (API 29) |
| Свободное место | ≈ 2 ГБ (веса и распаковка LLM) |

### Модели распознавания

12 файлов весов кладутся в `speech/src/main/assets/`. Архив `model-weights-v1.zip`
скачивается со [страницы релизов](https://github.com/god4claw/onwin/releases).

SHA-256: `85935bb391f80369a646b8b46b059521809f8da73ea1b4316a39776b9469e3ce`

```bash
unzip -o model-weights-v1.zip -d speech/src/main/assets
./gradlew :speech:verifyBundledModels   # должно быть 12 файлов
```

PowerShell:

```powershell
Expand-Archive -Path model-weights-v1.zip -DestinationPath speech/src/main/assets -Force
.\gradlew.bat :speech:verifyBundledModels
```

### Модель LLM

Локальная модель для правки расшифровки, резюме и протокола.

- **Модель:** Qwen2.5-0.5B-Instruct, ONNX GenAI, int4 (`cpu-int4-rtn-block-32-acc-level-4`).
- **Размер:** 10 файлов, 877 989 556 байт (~837 МиБ) в распакованном виде.
- **Лицензия:** Apache-2.0; базовые веса — Qwen/Qwen2.5-0.5B-Instruct.
- **Ассет:** `llm-model-v1.zip` — 509 493 843 байт (485,9 МиБ), SHA-256
  `eafc3c6fd909bdc684510011ba1d043aac7ca2f7ae33be1f9bd78d80f60630c5`.

Корень архива — `qwen2.5-0.5b-instruct-int4/`; внутри 10 файлов из `llm/models.sha256`.

Положите архив в корень проекта. Gradle включает его в APK, а приложение
распаковывает модель при первом использовании. Для ручных тестов сборок без
встроенного архива доступен каталог `/sdcard/Android/data/io.github.god4claw.onwin/files/llm`.

Подготовка отдельного комплекта для ручных тестов:

```powershell
Expand-Archive -Path llm-model-v1.zip -DestinationPath llm/models -Force
(Get-FileHash llm-model-v1.zip -Algorithm SHA256).Hash
.\gradlew.bat :llm:verifyLlmModel
pwsh -File llm/scripts/push-model.ps1
```

```bash
unzip -o llm-model-v1.zip -d llm/models
sha256sum llm-model-v1.zip
./gradlew :llm:verifyLlmModel
```

Альтернатива — Hugging Face:
[xiaoyao9184/Qwen2.5-0.5B-Instruct-onnx-genai](https://huggingface.co/xiaoyao9184/Qwen2.5-0.5B-Instruct-onnx-genai)
или `llm/scripts/fetch-model.ps1`.

### Сборка и установка

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Release (arm64-v8a, R8):

```powershell
.\gradlew.bat :app:assembleRelease
```

## Использование

1. **Первый запуск.** Экран приветствия → «Начать».
2. **Запись или импорт.**
   - «Записать» — разрешите доступ к микрофону. Запись продолжается в фоне и при
     выключенном экране.
   - «Импортировать с устройства» — выберите аудиофайл; он автоматически
     конвертируется в WAV 16 кГц.
3. **Расшифровка.** Переключатель «Расшифровка во время записи» включён по умолчанию —
   реплики появляются по мере речи. Выключите его, чтобы распознавание началось после
   кнопки «Стоп».
4. **Этапы обработки:** конвертация → определение говорящих → распознавание →
   коррекция текста (последний этап — если на устройство загружена LLM-модель).
5. **Результат.** Расшифровка с репликами по спикерам и таймкодами.

С готовой расшифровкой можно:

| Действие | Как |
|---|---|
| Переименовать спикера | Нажать на имя («Спикер N») |
| Перейти к моменту аудио | Нажать на текст реплики |
| Исправить реплику | Карандаш рядом с таймкодом |
| Послушать запись | «Воспроизвести» — текущая реплика подсвечивается |
| Экспортировать | Кнопка с папкой → формат (JSON/PDF/Markdown/TXT) → место сохранения |

JSON сохраняет массив `transcript` (текст, id спикера, время в мс); PDF, Markdown
и TXT — название записи, реплики, интервалы и имена спикеров.

### LLM-функции (при наличии модели)

После обработки на экране записи доступны:

- **«Кратко»** — резюме разговора;
- **чипы «Спикер N — Имя»** — предложенные имена, применение в один тап;
- **«Составить протокол»** — протокол совещания (кратко / решения / поручения)
  с экспортом в `.md`.

Всё считается локальной моделью на устройстве, без сети.

## Структура проекта

| Модуль | Назначение |
|---|---|
| `app` | Compose UI, Room, очередь обработки, foreground-сервис, экспорт |
| `speech` | Офлайн-движок: GigaAM-v3 (ASR), ERes2Net (диаризация), Silero VAD, sherpa-onnx |
| `llm` | Локальная LLM (Qwen2.5-0.5B, ONNX Runtime GenAI) |
| `baselineprofile` | Профили запуска (Baseline Profile) |

Подробнее о проекте — [`README.md`](../README.md).
