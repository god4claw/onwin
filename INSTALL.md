# Установка

Офлайн-приложение для расшифровки и диаризации русской речи на Android.

Цель и прогресс проекта — в [`README.md`](README.md). Устройство движка — [`speech/README.md`](speech/README.md).

- `:app` — Compose UI, Room и фоновая очередь обработки.
- `:speech` — on-device движок: GigaAM-v3 (ASR), ERes2Net speaker embeddings (диаризация), Silero VAD, sherpa-onnx.
- Сеть в рантайме не нужна: веса читаются из APK через `AssetManager`.

## Требования

- JDK 17.
- Android SDK 37.
- Для запуска на устройстве — `arm64-v8a`.

## 1. Клонирование

```bash
git clone https://github.com/god4claw/onwin.git
cd onwin
```

## 2. Веса

Веса (~350 МБ) не хранятся в git — их нужно скачать один раз.

> **Репозиторий приватный — скачивание только вручную, через браузер.**
> GitHub не поддерживает публичный доступ к отдельным файлам приватного
> репозитория, поэтому **прямой ссылки на веса не существует**: ни
> `Invoke-WebRequest`, ни `curl` без токена не скачают файл. Не тратьте время на
> подбор URL — их нет.

Порядок:

1. Откройте страницу релиза под своей учётной записью:
   **Releases → `speech-models-v1` → Assets → `speech-models-v1.zip`**
   (прямая ссылка для браузера:
   `https://github.com/god4claw/onwin/releases/tag/speech-models-v1`).
2. Скачайте `speech-models-v1.zip` (334 МиБ) и положите его в корень репозитория.
3. Распакуйте и проверьте:

PowerShell:

```powershell
Expand-Archive -Path "speech-models-v1.zip" -DestinationPath "speech/src/main/assets" -Force
.\gradlew.bat :speech:verifyBundledModels
```

bash:

```bash
unzip -o speech-models-v1.zip -d speech/src/main/assets
./gradlew :speech:verifyBundledModels
```

Архив содержит пять файлов моделей в подкаталогах `models/`, `asr/`, `vad/`
(см. таблицу в [`speech/README.md`](speech/README.md#модели)).

4. ERes2Net (speaker embedding по умолчанию) в архив `speech-models-v1` не входит —
   скачайте её отдельно, ссылка публичная (релиз sherpa-onnx):

```powershell
Invoke-WebRequest -Uri "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx" -OutFile "speech/src/main/assets/models/speaker_embedding_eres2net.onnx"
```

```bash
curl -L -o speech/src/main/assets/models/speaker_embedding_eres2net.onnx \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx"
```

   SHA-256: `c59158379255ad66e161679cca6af8d52d51e389e3224ab7d7a7baae295c2db5`, 26 485 263 байт.
   Без неё `verifyBundledModels` и `load()` падают со списком отсутствующих файлов.


Проверка целостности архива:

```powershell
(Get-FileHash speech-models-v1.zip -Algorithm SHA256).Hash
# 00020e21641414106a335ab4e5d2bfe18d91cc8a383d645a718344411c1c937b
```

> **CI.** В GitHub Actions автоматического скачивания нет: веса нужно либо
> закешировать как artifact, либо завести секрет с токеном и качать через
> asset API. Простого «без токена» в CI не бывает — это ограничение GitHub,
> а не репозитория.

`verifyBundledModels` проверяет наличие и SHA-256 всех шести файлов. Запускайте его
до сборки: без весов `:speech` соберётся, но упадёт в рантайме с
`IllegalStateException: В APK отсутствуют модели`.

Архив распаковывается **сразу в `speech/src/main/assets/`** — внутри него уже
лежат подкаталоги `models/`, `asr/`, `vad/`. Не распаковывайте во вложенную
папку: `verifyBundledModels` не найдёт файлы.

## 3. Сборка и установка

Debug-APK:

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Release-APK (arm64, уже с весами) лежит в Releases:

```powershell
adb install -r onwin-v0.1.0-arm64.apk
```

Для сборки release нужен `app/build.gradle.kts` с `noCompress += listOf("onnx", "txt")` — иначе sherpa-onnx не сможет читать веса из APK.

## API `:speech`

Batch:

```kotlin
val transcriber = SherpaTranscriber(context.applicationContext)
transcriber.load(SpeechConfig(numThreads = 4))

transcriber.transcribe(wav).collect { event ->
    when (event) {
        is TranscriptionEvent.SegmentReady -> Unit
        is TranscriptionEvent.Done -> Unit
        is TranscriptionEvent.Failed -> Unit
        is TranscriptionEvent.Progress -> Unit
    }
}

transcriber.release()
```

Streaming:

```kotlin
val streaming = SherpaStreamingTranscriber(context.applicationContext)
streaming.load(SpeechConfig(numThreads = 4))
streaming.transcribeStream(chunks).collect { event -> /* StreamingEvent */ }
```

- Batch принимает только канонический WAV: 16 кГц, моно, PCM16, 44-байтовый заголовок.
- Streaming принимает `Flow<FloatArray>`: 16 кГц, моно, `[-1..1]`.
- VAD по умолчанию: `threshold = 0.35`, `minSilenceMs = 450`, `minSpeechMs = 250`, `maxSpeechMs = 25_000`.

## Тесты

```powershell
.\gradlew.bat :speech:testDebugUnitTest
```

## Бенчмарк

Headless CLI: `speech/scripts/benchmark.ps1` и `speech/scripts/benchmark.sh`. Подробнее — [speech/BENCHMARK.md](speech/BENCHMARK.md).

## Структура

```text
app/                         UI, Room, очередь обработки
speech/                      офлайн-движок
speech/local-repo/           Maven-артефакт sherpa-onnx 1.13.0
speech/src/main/assets/      веса (игнорируются git)
```
