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
git clone https://github.com/god4claw/private-onwin.git
cd private-onwin
```

## 2. Веса

Веса (~290 МБ) не хранятся в git — их нужно скачать один раз.

> **Репозиторий приватный — скачивание только вручную, через браузер.**
> GitHub не поддерживает публичный доступ к отдельным файлам приватного
> репозитория, поэтому **прямой ссылки на веса не существует**: ни
> `Invoke-WebRequest`, ни `curl` без токена не скачают файл. Не тратьте время на
> подбор URL — их нет.

Порядок:

1. Откройте страницу релиза под своей учётной записью:
   **Releases → `speech-models-v1` → Assets → `speech-models-v1.zip`**
   (прямая ссылка для браузера:
   `https://github.com/god4claw/private-onwin/releases/tag/speech-models-v1`).
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

5. GigaAM-v3 e2e RNN-T (модель ASR по умолчанию) — публичный архив sherpa-onnx
   (162 МиБ). Из него нужны четыре файла под нашими именами:

```powershell
Invoke-WebRequest -Uri "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16.tar.bz2" -OutFile "rnnt.tar.bz2"
tar -xjf rnnt.tar.bz2
$r = "sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16"
Copy-Item "$r\encoder.int8.onnx" "speech\src\main\assets\models\gigaam_v3_e2e_rnnt_encoder_int8.onnx"
Copy-Item "$r\decoder.onnx" "speech\src\main\assets\models\gigaam_v3_e2e_rnnt_decoder.onnx"
Copy-Item "$r\joiner.onnx" "speech\src\main\assets\models\gigaam_v3_e2e_rnnt_joiner.onnx"
Copy-Item "$r\tokens.txt" "speech\src\main\assets\asr\gigaam_v3_e2e_rnnt_tokens.txt"
Remove-Item -Recurse rnnt.tar.bz2, $r
```

```bash
curl -L -o rnnt.tar.bz2 "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16.tar.bz2"
tar -xjf rnnt.tar.bz2
r=sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16
cp "$r/encoder.int8.onnx" speech/src/main/assets/models/gigaam_v3_e2e_rnnt_encoder_int8.onnx
cp "$r/decoder.onnx" speech/src/main/assets/models/gigaam_v3_e2e_rnnt_decoder.onnx
cp "$r/joiner.onnx" speech/src/main/assets/models/gigaam_v3_e2e_rnnt_joiner.onnx
cp "$r/tokens.txt" speech/src/main/assets/asr/gigaam_v3_e2e_rnnt_tokens.txt
rm -r rnnt.tar.bz2 "$r"
```

   Прежняя модель e2e CTC из архива v1 (`models/gigaam_v3_e2e_ctc_int8.onnx`,
   `asr/gigaam_v3_e2e_ctc_tokens.txt`) больше не обязательна: она нужна только при
   `SpeechConfig(asrModel = AsrModel.E2E_CTC)`. Всё, что лежит в assets, попадает
   в APK, поэтому без неё APK меньше на 305 МиБ — удалите эти два файла.


Проверка целостности архива:

```powershell
(Get-FileHash speech-models-v1.zip -Algorithm SHA256).Hash
# 00020e21641414106a335ab4e5d2bfe18d91cc8a383d645a718344411c1c937b
```

> **CI.** В GitHub Actions автоматического скачивания нет: веса нужно либо
> закешировать как artifact, либо завести секрет с токеном и качать через
> asset API. Простого «без токена» в CI не бывает — это ограничение GitHub,
> а не репозитория.

`verifyBundledModels` проверяет наличие и SHA-256 всех восьми файлов. Gradle
запускает эту проверку при сборке приложения, чтобы APK с отсутствующими или
повреждёнными весами не собрался. Проверку также можно запустить отдельно командой
`:speech:verifyBundledModels`.

Архив распаковывается **сразу в `speech/src/main/assets/`** — внутри него уже
лежат подкаталоги `models/`, `asr/`, `vad/`. Не распаковывайте во вложенную
папку: `verifyBundledModels` не найдёт файлы.

## 3. Сборка и установка

Debug-APK:

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### Профили запуска

На arm64-устройстве с Android 13+ и чистой установкой тестового пакета:

```powershell
.\gradlew.bat :app:generateReleaseBaselineProfile
.\gradlew.bat :baselineprofile:connectedBenchmarkReleaseAndroidTest
```

Для генерации на x86_64-эмуляторе с Android 13+ используйте
`.\gradlew.bat :app:generateReleaseBaselineProfile -PonwinProfileX86=true`.
Эта опция меняет ABI только у варианта для генерации, а APK для замеров
и распространения остаются arm64. Если подключено несколько устройств,
задайте `ANDROID_SERIAL` перед запуском.

Генератор использует отдельный пакет `com.example.onwin.profile`.
На первом запуске ему нужен экран приветствия. Для сценария импорта на
устройстве нужен системный выбор файлов с папкой «Загрузки» или Downloads.
Оставьте устройство разблокированным. Генератор сам создаёт короткий тестовый
WAV. Сохраните оба текстовых профиля из `app/src/release/generated/baselineProfiles/`
в Git. Для обновления профилей повторите генерацию после изменений сценариев.

Для замеров используйте физический телефон и сравните медиану холодного запуска
для двух режимов компиляции в JSON-отчёте Macrobenchmark. Отчёт и трассы лежат в
`baselineprofile/build/outputs/`. Обычная release-сборка использует уже
сохранённые профили и не требует подключённого телефона.

### Сборка и подпись release APK

`release` оптимизируется R8 и собирается без подписи. Перед сборкой проверьте веса
командой `:speech:verifyBundledModels`. Для распространения подпишите APK ключом релиза,
который хранится вне репозитория. Пароли вводите по запросу `apksigner`.

```powershell
.\gradlew.bat :app:assembleRelease :app:lintRelease :app:testReleaseUnitTest :app:analyzeReleaseR8Config

# apksigner и zipalign из Android SDK Build Tools должны быть доступны в PATH.
$apk = 'app\build\outputs\apk\release\app-release-unsigned.apk'
$signedApk = 'app\build\outputs\apk\release\private-onwin-arm64.apk'
zipalign -c -P 16 4 $apk
apksigner sign --ks $env:ONWIN_RELEASE_KEYSTORE --ks-key-alias $env:ONWIN_RELEASE_KEY_ALIAS --out $signedApk $apk
apksigner verify --verbose --print-certs $signedApk
adb install -r $signedApk
```

Перед распространением проверьте `assets/dexopt/baseline.prof` и
`lib/arm64-v8a/` в APK. Если предыдущий APK уже распространялся, сверьте
SHA-256 сертификата подписи и проверьте установку поверх предыдущей версии
на тестовом телефоне без удаления её данных. Проверьте импорт, микрофон,
завершение расшифровки, воспроизведение и сохранение JSON. Вместе с APK
сохраните `app/build/outputs/mapping/release/mapping.txt` и отчёты замеров.
Для Sherpa файлы `onnx` и `txt` упаковываются без сжатия.

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
