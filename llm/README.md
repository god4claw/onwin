# :llm — локальная LLM для правки расшифровки и протокола

Модуль подставляет в `:speech` реализацию `TextGenerator` поверх ONNX Runtime GenAI.
Промпт, маски чисел, гард и разбор ответа живут в `:speech` ([`../docs/benches/llm-correction.md`](../docs/benches/llm-correction.md));
здесь — только рантайм, поиск модели и сборка корректора.

## Что внутри

| Класс | Роль |
|---|---|
| `OnnxGenAiGenerator` | `TextGenerator`: открывает каталог модели ONNX GenAI, оборачивает промпт в chat-шаблон, генерирует жадно, проверяет отмену корутины на каждом токене. |
| `ChatMessages` | JSON сообщений для `applyChatTemplate`: в 0.19 это массив сообщений, а не пара «system/user». Невалидный JSON роняет процесс в нативном коде, поэтому строка собирается и проверяется JVM-тестом. |
| `OnDeviceLlm` | Сборка на одной модели: `corrector` (словарь + `LlmCorrector`), `analyzer` (имена и резюме) и `protocolAnalyzer` (опциональный протокол). Нет модели — `null`, приложение работает как раньше. |
| `LlmModelDirectory` | Дешёвая проверка комплекта: `genai_config.json` + хотя бы один `*.onnx`. |

## Проходы по одной модели

1. **Правка реплик** — `LlmCorrector` по одной реплике, с масками чисел и гардом. Для английского не запускается: русский промпт может испортить текст.
2. **Разбор диалога** — `LlmDialogueAnalyzer` по всему тексту: режет диалог на куски
   по бюджету символов, собирает имена спикеров из всех кусков (ничья голосов —
   отказ, лучше без имени, чем с выдуманным) и делает резюме на 3–5 предложений.
   Резюме с эхом расшифровки, без букв нужного языка или короче 40 символов отбрасывается.
3. **Протокол (опционально)** — `analyzeProtocol` по кнопке: участники берутся из
   сегментов и уже извлечённых имён, модель пишет только `КРАТКО/РЕШЕНИЯ/ПОРУЧЕНИЯ`
   (или английские секции). JSON не просим: ORT GenAI 0.19 без GBNF, 0.5B его ломает.

Язык (RU/EN/MIXED) определяется по тексту и выбирает промпт: русский, английский
или русский с пометкой «термины не переводи».

Генератор один: загрузить 0,5B дважды — это лишние секунды и двойная память.
Результат второго прохода сохраняется в Room (`session_notes`, `speaker_suggestions`),
протокол — в `session_protocols` (Room v3). На экране записи: блок «Кратко», чипы
«Спикер N — Имя» и кнопка «Составить протокол» с экспортом `.md`.
Длинный протокол прокручивается внутри блока. Кнопка «Сохранить .md» находится внизу блока.

## Модель

Qwen2.5-0.5B-Instruct, ONNX GenAI, int4 (`cpu-int4-rtn-block-32-acc-level-4`) — **~0,9 ГБ**
в распакованном виде (10 файлов, 877 989 556 байт).

Готовый архив — в релизе [`llm-model-v1`](https://github.com/god4claw/onwin/releases/tag/llm-model-v1)
(репозиторий приватный, скачивание под своей учётной записью): `llm-model-v1.zip`,
509 493 843 байт (485,9 МиБ), SHA-256
`eafc3c6fd909bdc684510011ba1d043aac7ca2f7ae33be1f9bd78d80f60630c5`.
Для сборки приложения положите ZIP в корень репозитория. Gradle проверяет хеш и
включает ZIP в APK. При первом запросе модель распаковывается в `noBackupFilesDir/llm/v1`.
Загрузка весов с устройства и вывод модели работают офлайн.

Для отдельных инструментальных тестов модуля `:llm` можно распаковать архив в
`llm/models` и залить комплект в тестовый пакет:

```powershell
# из корня репозитория
Expand-Archive -Path llm-model-v1.zip -DestinationPath llm/models -Force
(Get-FileHash llm-model-v1.zip -Algorithm SHA256).Hash
# eafc3c6fd909bdc684510011ba1d043aac7ca2f7ae33be1f9bd78d80f60630c5
.\gradlew.bat :llm:verifyLlmModel          # проверить комплект
pwsh -File llm/scripts/push-model.ps1 -Package com.example.llm.test
```

```bash
unzip -o llm-model-v1.zip -d llm/models
sha256sum llm-model-v1.zip
./gradlew :llm:verifyLlmModel
```

Альтернатива — Hugging Face:
[xiaoyao9184/Qwen2.5-0.5B-Instruct-onnx-genai](https://huggingface.co/xiaoyao9184/Qwen2.5-0.5B-Instruct-onnx-genai/tree/main/cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4),
каталог `cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4`. `fetch-model.ps1` скачивает
файлы оттуда, сверяет SHA-256 с `llm/models.sha256` и раскладывает в `llm/models`:

```powershell
pwsh -File llm/scripts/fetch-model.ps1
.\gradlew.bat :llm:verifyLlmModel
pwsh -File llm/scripts/push-model.ps1 -Package com.example.llm.test
```

Сборки и тесты без встроенного архива могут использовать распакованную модель:

1. `filesDir/llm` — внутренний каталог приложения;
2. `getExternalFilesDir(null)/llm` — каталог пакета, указанный в `push-model.ps1`.

## Рантайм и размер

- `ai.onnxruntime.genai:onnxruntime-genai-android:0.19.0` — адаптированный AAR из
  [`local-repo`](local-repo) на базе релиза Microsoft `v0.19.0`;
- `com.microsoft.onnxruntime:onnxruntime-android:1.31.1` — ядро ORT, которое грузит GenAI.

Нативное ядро `libonnxruntime-genai.so` пересобрано для `arm64-v8a` и `x86_64` из
тега Microsoft `v0.19.0` (`d01b2441a72d4e3223415b9b08474c6e65fbc028`) с Android NDK
`28.2.13676358`, `ANDROID_PLATFORM=android-29` и `ORT_HOME` из распакованного AAR ONNX
Runtime `1.31.1`. Цель CMake `onnxruntime-genai` собрана в режиме Release с `USE_CUDA=OFF`,
`ENABLE_TELEMETRY=OFF`, `ENABLE_JAVA=OFF`, `ENABLE_PYTHON=OFF` и `ENABLE_TESTS=OFF`.
Оригинальные Java- и JNI-биндинги сохранены. В AAR убраны
`libmat.so` с выравниванием ELF-сегментов 4 КБ и декларации её провайдера и разрешений.
При замене AAR проверьте выравнивание ELF (`llvm-readelf -Wl`) и упаковки APK
(`zipalign -c -P 16 -v 4`), затем запустите генерацию на устройстве с 16-КБ страницами.

Провайдер отдельного AAR ONNX Runtime исключён через `tools:node="remove"` в
[`llm/src/main/AndroidManifest.xml`](src/main/AndroidManifest.xml). Сетевые разрешения
исключены в [`app/src/main/AndroidManifest.xml`](../app/src/main/AndroidManifest.xml).
Проверяйте итоговый манифест APK после `:app:processDebugMainManifest`.

ABI: `arm64-v8a` и `x86_64`. 32-битные библиотеки исключены: sherpa-onnx и onnxruntime-android
везут разные `libonnxruntime.so` для x86, и они конфликтуют по имени файла.
В debug APK включены обе 64-битные ABI, чтобы x86_64-эмулятор запускал ONNX Runtime
напрямую. Обычный release APK по-прежнему собирается для `arm64-v8a`.

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
см. [`../docs/benches/llm-correction.md`](../docs/benches/llm-correction.md), раздел «Как проверять».
