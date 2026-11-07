# :llm — локальная LLM для правки расшифровки

Модуль подставляет в `:speech` реализацию `TextGenerator` поверх ONNX Runtime GenAI.
Промпт, маски чисел, гард и разбор ответа живут в `:speech` ([`docs/llm-correction.md`](../docs/llm-correction.md));
здесь — только рантайм, поиск модели и сборка корректора.

## Что внутри

| Класс | Роль |
|---|---|
| `OnnxGenAiGenerator` | `TextGenerator`: открывает каталог модели ONNX GenAI, оборачивает промпт в chat-шаблон, генерирует жадно, проверяет отмену корутины на каждом токене. |
| `ChatMessages` | JSON сообщений для `applyChatTemplate`: в 0.19 это массив сообщений, а не пара «system/user». Невалидный JSON роняет процесс в нативном коде, поэтому строка собирается и проверяется JVM-тестом. |
| `OnDeviceLlm` | Сборка на одной модели: `corrector` (словарь + `LlmCorrector`) и `analyzer` (`LlmDialogueAnalyzer` — имена спикеров и резюме). Нет модели — `null`, приложение работает как раньше. |
| `LlmModelDirectory` | Дешёвая проверка комплекта: `genai_config.json` + хотя бы один `*.onnx`. |

## Два прохода по одной модели

1. **Правка реплик** — `LlmCorrector` по одной реплике, с масками чисел и гардом.
2. **Разбор диалога** — `LlmDialogueAnalyzer` по всему тексту: режет диалог на куски
   по бюджету символов, собирает имена спикеров из всех кусков (ничья голосов —
   отказ, лучше без имени, чем с выдуманным) и делает резюме на 3–5 предложений.
   Резюме с эхом расшифровки, без кириллицы или короче 40 символов отбрасывается.

Генератор один: загрузить 0,5B дважды — это лишние секунды и двойная память.
Результат второго прохода сохраняется в Room (`session_notes`, `speaker_suggestions`)
и показывается на экране записи: блок «Кратко» и чипы «Спикер N — Имя», по нажатию
имя применяется как обычное переименование.

## Модель

Qwen2.5-0.5B-Instruct, ONNX GenAI, int4 (`cpu-int4-rtn-block-32-acc-level-4`) — **~0,9 ГБ**,
в APK не входит и в git не хранится.

```powershell
pwsh -File llm/scripts/fetch-model.ps1     # скачать и сверить SHA-256 (llm/models.sha256)
.\gradlew.bat :llm:verifyLlmModel          # проверить комплект
pwsh -File llm/scripts/push-model.ps1      # залить на устройство
```

Приложение ищет модель в двух местах по порядку:

1. `filesDir/llm` — внутренний каталог приложения;
2. `getExternalFilesDir(null)/llm` — `/sdcard/Android/data/com.example.onwin/files/llm`, туда пишет `push-model.ps1`.

## Рантайм и размер

- `ai.onnxruntime.genai:onnxruntime-genai-android:0.19.0` — AAR из GitHub Releases
  `microsoft/onnxruntime-genai`, лежит в [`local-repo`](local-repo) (21 МБ);
- `com.microsoft.onnxruntime:onnxruntime-android:1.31.1` — ядро ORT, которое грузит GenAI.

AAR объявляет `INTERNET`, `ACCESS_NETWORK_STATE` и ContentProvider телеметрии `libmat`.
Приложение полностью офлайн, поэтому разрешения и провайдер вырезаны в
[`app/src/main/AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) через `tools:node="remove"`.
Сборка `:app:processDebugMainManifest` — проверка, что в merged-манифесте не осталось `INTERNET`.

ABI: `arm64-v8a` и `x86_64`. 32-битные библиотеки исключены: sherpa-onnx и onnxruntime-android
везут разные `libonnxruntime.so` для x86, и они конфликтуют по имени файла.

## Тесты

- JVM: `LlmModelDirectoryTest`, `ChatMessagesTest`.
- Instrumented (нужна модель на устройстве): `OnnxGenAiGeneratorDeviceTest` — генерация
  кириллицы и полный стек `LlmCorrector` (промпт → маски → генерация → гард).
```powershell
.\gradlew.bat :llm:testDebugUnitTest
# Модель уже залита в /sdcard/Android/data/com.example.llm.test/files/llm:
.\gradlew.bat :llm:connectedDebugAndroidTest
```

## Проверено

Pixel_8_API36 (x86_64, Android 16), 2026-11-07:

- модель грузится, генерация идёт ~10–15 с на реплику (включая загрузку модели один раз);
- без примеров в промпте Qwen2.5-0.5B переписывает фразу по смыслу
  («я придёт завтра в девять» → «я буду работать завтра в десять»);
- с двумя примерами в промпте модель возвращает текст дословно и не выдумывает;
- гард `CorrectionGuard` в обоих случаях не пропускает переписывание: пользователь
  видит исходный текст ASR.

Качество правки (WER до/после) не измерено: нужен эталонный набор реплик,
см. [`docs/llm-correction.md`](../docs/llm-correction.md), раздел «Как проверять».
