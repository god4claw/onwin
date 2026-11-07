# `:speech` — офлайн-распознавание и диаризация речи

ML-модуль приложения `private-onwin`: русская речь → текст с таймкодами и метками говорящих. Считается на устройстве, сеть в рантайме не нужна. Веса лежат в APK и читаются через `AssetManager`.

- **Файл (рекомендуется): `SherpaVadTranscriber`** — готовый WAV через пайплайн streaming без пауз: АРУ → VAD → ASR по репликам → спикеры по всей записи. На наших замерах точнее и вдвое быстрее batch и не падает на монологе — [`docs/bench-vad-transcriber.md`](../docs/bench-vad-transcriber.md).
- **Batch:** готовый WAV → диаризация pyannote → сегменты с текстом (`SherpaTranscriber`).
- **Streaming (псевдостриминг):** поток PCM 16 кГц → VAD → распознавание каждой реплики → онлайн-кластеризация говорящих → в конце пересчёт спикеров по всем репликам.
- Стек: GigaAM-v3 e2e RNN-T (ASR; e2e CTC — запасной вариант), speaker embedding ERes2Net (диаризация; CAM++ — запасной вариант), pyannote segmentation (только batch), Silero VAD, sherpa-onnx 1.13.0.
- `minSdk 29`, `compileSdk 37`, Kotlin 2.4.21, JDK 17.

Модуль отвечает за ML и чтение WAV. UI, Room, очередь, foreground-сервис, разрешения, декодирование и ресемплинг, хранение файлов и экспорт — на стороне приложения.

## Содержание

- [Что делает модуль](#что-делает-модуль)
- [Публичный API](#публичный-api)
- [Batch](#batch)
- [Streaming](#streaming)
- [Модели](#модели)
- [Сборка и тесты](#сборка-и-тесты)
- [Устройство модуля](#устройство-модуля)
- [Ограничения](#ограничения)
- [Производительность](#производительность)
- [Интеграция в приложение](#интеграция-в-приложение)
  - [Подключение](#подключение)
  - [Точка замены](#точка-замены)
  - [Очередь и отмена](#очередь-и-отмена)
  - [Ошибки](#ошибки)
  - [Память и процесс](#память-и-процесс)
  - [Диагностика](#диагностика)
  - [Чек-лист](#чек-лист)

## Что делает модуль

### Batch-пайплайн

```text
WAV 16 кГц моно PCM16
   → pyannote segmentation + speaker embeddings (ERes2Net) + FastClustering
   → сегменты с номерами говорящих
   → ASR GigaAM-v3 по каждому сегменту (куски до 45 с, разрез по тихой границе)
   → Flow<TranscriptionEvent>: прогресс, сегменты по мере готовности, Done
```

Спикеры нумеруются `0..N-1` в порядке первого появления. Таймкоды — миллисекунды от начала файла. Короткие обрывки диаризации (< 0,7 с) приклеиваются к соседней длинной реплике, если разрыв не больше 0,25 с и сосед больше чем вдвое длиннее. Сегменты короче 200 мс и пустой текст не отдаются.

### Streaming-пайплайн

```text
Flow<FloatArray> 16 кГц моно, [-1..1], любой размер чанка
   → АРУ для VAD: тихая речь подтягивается к −20 dBFS (AgcConfig);
     ASR и embedding дальше получают исходный сигнал тех же отрезков
   → Silero VAD (окно 512 сэмплов)
   → SpeechStarted при начале речи
   → по тишине (450 мс) или максимуму (25 с) VAD-чанк уходит в обработку;
     длинный сегмент VAD режется в самом тихом месте последних 10 с перед лимитом
   → ASR всего чанка с метками времени слов; границы реплики — по словам
   → смена говорящего внутри чанка (ERes2Net по окнам) → разрез по тихому
     кадру делит слова на реплики, звук для ASR не режется. Если целый чанк
     распознан разреженно (< 2 слов/с — так бывает на наложении голосов) и
     куски по отдельности дают больше слов, текст берётся из кусков
   → speaker embedding (ERes2Net) по участку со словами + онлайн-кластеризация
   → Utterance с текстом, таймкодами и предварительным номером говорящего
   → после конца входного Flow: перекластеризация всех embedding-ов сессии,
     соседние куски одного чанка с одним спикером склеиваются
   → Done с финальными номерами говорящих
```

Это не потоковая выдача слов: промежуточных гипотез нет, текст появляется после окончания реплики. Задержка — хвост VAD-тишины плюс время распознавания реплики.

## Публичный API

`com.example.speech.api` — контракт. `com.example.speech.impl` — реализации. `com.example.speech.audio` и `com.example.speech.data` — `internal`, приложению недоступны (веса всегда из assets, сетевой загрузки нет).

```kotlin
interface Transcriber {
    suspend fun load(config: SpeechConfig)
    fun transcribe(wav: File, expectedSpeakers: Int?): Flow<TranscriptionEvent>
    fun transcribe(wav: File): Flow<TranscriptionEvent> = transcribe(wav, null)
    fun release()
}

class SherpaTranscriber(context: Context) : Transcriber      // pyannote + FastClustering
class SherpaVadTranscriber(context: Context) : Transcriber   // VAD-пайплайн, рекомендуется для файлов

interface StreamingTranscriber {
    suspend fun load(config: SpeechConfig)
    fun transcribeStream(audio: Flow<FloatArray>): Flow<StreamingEvent>
    fun release()
}

class SherpaStreamingTranscriber(context: Context) : StreamingTranscriber
```

```kotlin
data class Segment(
    val speaker: Int,   // 0..N-1, стабилен в пределах одной расшифровки/стрима
    val startMs: Long,  // от начала аудио
    val endMs: Long,
    val text: String,
)

enum class Stage { DIARIZATION, RECOGNITION }

sealed interface TranscriptionEvent {
    data class Progress(val stage: Stage, val fraction: Float) : TranscriptionEvent
    data class SegmentReady(val segment: Segment) : TranscriptionEvent
    data class Done(val segments: List<Segment>) : TranscriptionEvent
    data class Failed(val cause: Throwable) : TranscriptionEvent
}

sealed interface StreamingEvent {
    data class SpeechStarted(val startMs: Long) : StreamingEvent
    data class Utterance(val segment: Segment) : StreamingEvent
    data class Done(val segments: List<Segment>) : StreamingEvent
    data class Failed(val cause: Throwable) : StreamingEvent
}
```

`SpeechConfig`:

| Поле | По умолчанию | Смысл |
|---|---|---|
| `numThreads: Int` | `4` | Потоки ASR и диаризации (VAD — всегда 1), `>= 1` |
| `asrModel` | `E2E_RNNT` | `E2E_RNNT` (GigaAM-v3 e2e RNN-T, по умолчанию) или `E2E_CTC` (прежняя модель; её файлы в APK необязательны). RNN-T точнее: WER синтетики 16,2 → 15,1 %, числа верно по значению 85 → 93 % — [`docs/bench-asr-rnnt.md`](../docs/bench-asr-rnnt.md) |
| `embeddingModel` | `ERES2NET` | `ERES2NET` (`speaker_embedding_eres2net.onnx`) или `CAMPLUS` (`speaker_embedding_camplus.onnx`). CAM++ обучена на китайской речи и путает два женских голоса — [`docs/bench-eres2net.md`](../docs/bench-eres2net.md) |
| `autoClusterThreshold` | `0.80f` | Distance threshold для batch-авторежима: **ниже → больше говорящих**. При заданном `expectedSpeakers` не используется |
| `onlineClusterThreshold` | `0.45f` | Косинусный порог streaming-кластеризации: **выше → больше говорящих**, `0..1` |
| `maxOnlineSpeakers` | `20` | Потолок говорящих в стриме, `>= 1` |
| `speakerReclusterThreshold` | `0.65f` | Streaming: порог финальной перекластеризации в `Done`, **выше → больше говорящих**, `0..1` |
| `speakerMinShare` | `0.15f` | Streaming: спикер с долей речи меньше этой в `Done` вливается в самого похожего, `0..0.5`. Лишние кластеры на шуме — частая ошибка ERes2Net; цена — тихий участник группы, говорящий < 15 % времени |
| `vad: VadConfig` | см. ниже | Только streaming |
| `agc: AgcConfig` | см. ниже | АРУ перед VAD, только streaming |

`VadConfig`: `threshold = 0.35f`, `minSilenceMs = 450`, `minSpeechMs = 250`, `maxSpeechMs = 25_000`. Порог ниже стандартного для Silero (0,5): при 0,5 терялась речь в шуме — [`docs/bench-vad-noise.md`](../docs/bench-vad-noise.md).

`AgcConfig`: `enabled = true`, `targetDbfs = -20f`, `maxGainDb = 24f`. Без АРУ Silero VAD пропускал собеседника, который сидит дальше от микрофона (−33…−37 dBFS при фоне −47): на реальной записи терялось ~9 с разборчивой речи, и порог VAD 0,25 не помогал. АРУ работает только по прошлому сигналу, без задержки; громкую речь не приглушает; на кадрах не громче фона усиление не растёт, а в паузах возвращается к 0 дБ. Выровненный сигнал видит только VAD: ASR и embedding получают исходный отрезок (кольцевой буфер на 90 с), иначе меняющееся усиление и поднятый фон сливали спикеров. Выключить — `AgcConfig(enabled = false)`.

**Номера спикеров в streaming.** `Utterance` приходит с предварительной меткой онлайн-кластеризатора: он решает по одной реплике и только по прошлому. В `Done` все реплики сессии перекластеризуются разом (`SpeakerReclusterer`), и номера могут измениться — UI должен заменить метки по `Done`. Зачем: онлайн-кластеризатор ждёт 2,5 с речи нового голоса и сливал собеседника, который отвечает коротко («да», «понятно»), с основным. На отложенной выборке (18 синтетических разговоров, параметры подбирались на другом наборе) ошибка атрибуции 25,1 → 12,7 %, верное число спикеров 9 → 13 из 18 — [`docs/bench-recluster.md`](../docs/bench-recluster.md). Порог `clusterPruneMinMs`/`clusterPruneMinFraction` теперь действует только в batch.

`SpeechConfig` и `VadConfig` проверяют значения в `init`: `numThreads >= 1`, `autoClusterThreshold` и `onlineClusterThreshold` в `0..1`, `maxOnlineSpeakers >= 1`, `threshold` в `0..1`, `minSilenceMs >= 0`, `minSpeechMs >= 0`, `maxSpeechMs > minSpeechMs`. Отдельных проверок в `load()` больше нет.

Веса всегда поставляются в assets APK; `ModelRegistry` с размерами и SHA-256, а также `BundledModels` — `internal`. Сетевой загрузки нет. Полный список и SHA-256 — в `speech/models.sha256`; размеры — в таблице ниже.

## Batch

### `load(config)`

- `suspend`, main-safe, идемпотентен: повторный вызов с тем же «ключом моделей» — no-op. Ключ — только то, что влияет на native-объекты (`numThreads`, `asrModel`, `embeddingModel`, `autoClusterThreshold`); изменение `vad` или runtime-порогов не перезагружает веса, а обновляет сохранённый конфиг.
- Холодная загрузка: 5–10 с, ~450 МБ PSS (в основном native).
- Бросает исключения, а не отдаёт их через `Flow`: `IllegalArgumentException` — уже при создании невалидного `SpeechConfig` (проверки в `init`), `IllegalStateException`, если размеры весов не совпадают или их нет, плюс ошибки native.
- Вызывать до первого `transcribe()`. Без `load()` расшифровка отдаст `Failed(IllegalStateException("Модели не загружены..."))`.
- Диаризаторы кешируются по числу кластеров (LRU на два варианта): переключение «авто → 2 → 3 → 2» не перезагружает embedding-модель.

### `transcribe(wav)`

- Холодный `Flow`: работа начинается при `collect`. Отмена коллектора останавливает работу.
- События:
  - `Progress(stage, fraction)` — `fraction` в `0..1` внутри этапа. При `expectedSpeakers = 1` диаризация пропускается, поэтому `DIARIZATION` быстро проходит 0 → 1.
  - `SegmentReady` — сегменты по мере распознавания, в порядке времени. Можно сразу писать в БД.
  - `Done(segments)` — финальный список. Нужно **заменять** им накопленные сегменты, а не дописывать.
  - `Failed(cause)` — терминальное событие, после него Flow завершается. Частичные сегменты, пришедшие до ошибки, остаются валидными.
- Если Flow завершился без `Done` и без `Failed` — это ошибка (`TRANSCRIPTION_FAILED`).
- Одновременные вызовы сериализуются внутренним `Mutex`, но приложению всё равно нужна отдельная очередь: две расшифровки параллельно запускать не стоит.
- Отмена кооперативная: проверка идёт между сегментами. Диаризация — один блокирующий native-вызов, поэтому отмена во время неё вступит в силу только после завершения. В UI лучше показывать «Останавливаем…», а не ждать мгновенного `CANCELLED`.

### `transcribe(wav, expectedSpeakers)`

| Значение | Поведение |
|---|---|
| `null` | Автоопределение числа говорящих |
| `1` | Диаризация пропускается, весь файл — один говорящий. Заметно быстрее |
| `2..20` | Диаризация с фиксированным числом кластеров |
| иное | `Failed(IllegalArgumentException)`, Flow завершается |

Если число участников известно, лучше передать его: результат стабильнее. Для `1` это ещё и заметно быстрее; для `2..20` основной выигрыш — стабильность, не скорость.

В текущем `DefaultProcessingController` число не передаётся: вызывается `transcribe(wav)`, то есть авто-режим. Если в UI появится выбор числа говорящих, его нужно протянуть через `QueuedJob`/`process` в `transcribe(wav, expectedSpeakers)`.

### `release()`

- Не `suspend`, идемпотентен, освобождает native-память моделей (большую часть ~450 МБ PSS).
- **Безопасен во время работы.** Если в этот момент идёт расшифровка, освобождение откладывается и выполняется в её `finally`, под тем же `Mutex`: освобождать native-объекты под работающим JNI-вызовом нельзя. `load()` отменяет отложенный запрос — новая загрузка важнее старого `release()`.
- Вызывать после завершения Flow, когда очередь пуста, и в `finally`.
- Без `release()` память не вернётся: финализаторов и `Closeable` нет.

### Входной WAV

| Параметр | Требование |
|---|---|
| Контейнер | `RIFF/WAVE`, `fmt ` ровно 16 байт на смещении 12 |
| `data` | сразу на смещении 36, без `LIST`/`fact` и других чанков |
| Формат | PCM, знаковый 16 бит, little-endian |
| Каналы | 1 (моно) |
| Частота | 16 000 Гц |
| Длительность | `SherpaVadTranscriber` — до 12 часов, файл читается кусками; batch `SherpaTranscriber` — до 60 минут, файл читается целиком |
| Чтение файла | приложение; `:speech` только читает |

`AudioConverter` в приложении уже выдаёт такой файл. Любой другой источник (галерея, шаринг, запись) сначала нужно прогнать через него. WAV с чанками между `fmt` и `data` (например, `LIST`), стерео или 44,1 кГц будет отклонён с `Failed(IllegalArgumentException)`.

Конвертер приложения принимает файлы до ~18,6 ч, `SherpaVadTranscriber` — до 12 ч, batch — только до 60 минут. На эмуляторе проверена запись на 76 минут: WER и атрибуция спикеров те же, что на тех же диалогах по отдельности, память не растёт ([`docs/bench-long-audio.md`](../docs/bench-long-audio.md)). Длительность лучше проверять до `transcribe()`, иначе `Failed` придёт уже после конвертации.

## Streaming

```kotlin
import com.example.speech.api.StreamingEvent
import com.example.speech.impl.SherpaStreamingTranscriber

val streaming = SherpaStreamingTranscriber(context.applicationContext)
try {
    streaming.load(SpeechConfig(numThreads = 4))
    streaming.transcribeStream(audioFlow).collect { event ->
        when (event) {
            is StreamingEvent.SpeechStarted -> showRecordingIndicator(event.startMs)
            is StreamingEvent.Utterance -> addSegment(event.segment)
            is StreamingEvent.Done -> finish(event.segments)
            is StreamingEvent.Failed -> showError(event.cause)
        }
    }
} finally {
    streaming.release()
}
```

Вход:

- `Flow<FloatArray>`, 16 кГц, моно, значения `[-1..1]`.
- Размер чанка любой; рекомендуется 100 мс = 1600 сэмплов. VAD сам собирает окна по 512 сэмплов.
- Если микрофон отдаёт 44,1/48 кГц — ресемплировать в приложении (`StreamingResampler` уже есть). Модуль не проверяет частоту и амплитуду: при ошибке вход просто деградирует по качеству.
- `AudioRecord` и разрешение `RECORD_AUDIO` — ответственность приложения. Источник аудио не должен блокировать поток сбора: лучше `callbackFlow` и отдельный диспетчер для чтения микрофона.

События:

| Событие | Смысл |
|---|---|
| `SpeechStarted(startMs)` | Переход «тишина → речь»; за один стрим может быть несколько раз |
| `Utterance(segment)` | Готовая реплика: текст + таймкоды + спикер |
| `Done(segments)` | Входной Flow завершён, все реплики обработаны |
| `Failed(cause)` | Терминальная ошибка |

Правила:

- Промежуточных гипотез нет: `Utterance` приходит после тишины `minSilenceMs` (450 мс), принудительного разреза на `maxSpeechMs` (25 с) или по завершении входного Flow (`flush`).
- Спикеры нумеруются с 0 в порядке появления и стабильны внутри одного стрима. Число говорящих ограничено `maxOnlineSpeakers`; порог — `onlineClusterThreshold` (выше → больше спикеров).
- Реплики короче 500 мс не дают embedding и наследуют последнего говорящего. Если говорящих ещё нет, такая реплика получает спикера 0.
- Для бесконечного микрофона `Done` не будет — остановка только отменой коллектора.
- `release()` безопасен и во время стрима: он не трогает native-объекты, а запоминает запрос, и стрим освобождает модели в своём `finally`. После такого `release()` следующий `transcribeStream()` потребует нового `load()`.
- `load()` сериализован с активной сессией: во время стрима он подождёт её конца. Для бесконечного микрофона это значит, что `load()` не вернётся, пока стрим не отменят: вызывайте `load()` до старта стрима.
- Второй одновременный стрим не ждёт молча: он сразу получает `Failed(«Уже идёт другая streaming-сессия»)`.
- `load()` поднимает ASR + VAD + embedding (2–3 с, ~450 МБ). Segmentation-модель в streaming в рантайме не используется, но `load()` всё равно проверяет наличие всех восьми обязательных файлов.
- Batch и streaming одновременно — две копии моделей и ~2× память. Лучше последовательно.

Настройка VAD:

```kotlin
SpeechConfig(
    vad = VadConfig(
        threshold = 0.35f,      // 0..1, выше → меньше ложных срабатываний, но теряется тихая речь
        minSilenceMs = 450,     // тишина, закрывающая реплику
        minSpeechMs = 250,      // короче — не считаем речью
        maxSpeechMs = 25_000,   // принудительный разрез длинной речи
    ),
)
```

## Модели

Восемь файлов, ~275 МиБ (288,8 МБ) в APK. В git не хранятся.

| Файл в `speech/src/main/assets/` | Размер, байт | Назначение |
|---|---:|---|
| `models/gigaam_v3_e2e_rnnt_encoder_int8.onnx` | 224 570 820 | ASR GigaAM-v3 e2e RNN-T, encoder |
| `models/gigaam_v3_e2e_rnnt_decoder.onnx` | 4 600 132 | ASR, decoder |
| `models/gigaam_v3_e2e_rnnt_joiner.onnx` | 2 712 896 | ASR, joiner |
| `asr/gigaam_v3_e2e_rnnt_tokens.txt` | 13 354 | токены ASR |
| `models/speaker_embedding_camplus.onnx` | 28 281 138 | speaker embedding CAM++ (запасной) |
| `models/speaker_embedding_eres2net.onnx` | 26 485 263 | speaker embedding ERes2Net (по умолчанию) |
| `asr/segmentation.onnx` | 1 540 506 | pyannote segmentation |
| `vad/silero_vad.onnx` | 643 854 | Silero VAD |

Необязательные — только для `AsrModel.E2E_CTC`: `models/gigaam_v3_e2e_ctc_int8.onnx`
(319 869 121) и `asr/gigaam_v3_e2e_ctc_tokens.txt` (2 262). Без них APK меньше на 305 МиБ.

Получить и распаковать:

> **Скачивание только вручную.** Репозиторий приватный, и GitHub не даёт
> публичного доступа к отдельным файлам. Прямой ссылки на архив **нет**: ни
> `Invoke-WebRequest`, ни `curl` без токена его не скачают — не подбирайте URL.
>
> 1. Откройте под своей учётной записью:
>    `https://github.com/god4claw/private-onwin/releases/tag/speech-models-v1`
> 2. Assets → `speech-models-v1.zip` → скачайте в корень репозитория.
> 3. Распакуйте (внутри уже есть `models/`, `asr/`, `vad/`):

```powershell
Expand-Archive -Path "speech-models-v1.zip" -DestinationPath "speech/src/main/assets" -Force
.\gradlew.bat :speech:verifyBundledModels
```

```bash
unzip -o speech-models-v1.zip -d speech/src/main/assets
./gradlew :speech:verifyBundledModels
```

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

SHA-256 архива — `00020e21641414106a335ab4e5d2bfe18d91cc8a383d645a718344411c1c937b`.
Для CI автоматического скачивания нет: нужен либо закешированный artifact, либо
секрет с токеном. Подробности — в корневом [`README.md`](../README.md#2-веса).

`verifyBundledModels` проверяет наличие и SHA-256 всех восьми файлов (манифест — `speech/models.sha256`). В рантайме `load()` проверяет точные размеры через `AssetManager`; при несовпадении бросает `IllegalStateException` со списком отсутствующих файлов.

`load()` проверяет весь набор в обоих режимах: batch не использует VAD, streaming — segmentation, но оба файла всё равно должны лежать в assets.

Веса добавят к APK примерно 350 МБ (они не сжимаются). Для Google Play это больше лимита базового модуля: потребуется install-time asset pack или другая схема поставки весов. Сейчас она не реализована — это стоит учитывать при планировании релиза.

## Сборка и тесты

```powershell
.\gradlew.bat :speech:verifyBundledModels
.\gradlew.bat :speech:testDebugUnitTest
.\gradlew.bat :speech:assembleDebug
```

JVM-тесты не требуют весов: проверяются VAD-сегментация, онлайн-кластеризация, LRU-кеш диаризаторов, отложенный `release()`, «ключи» конфига, чтение WAV и обработка стримингового чанка (`StreamingChunkProcessor`). Инструментальный бенчмарк — `speech/scripts/benchmark.ps1` / `.sh`, подробности в [`BENCHMARK.md`](BENCHMARK.md).

`:speech` зависит от `sherpa-onnx:1.13.0` из `speech/local-repo`; репозиторий подключён в `settings.gradle.kts`. Модуль не публикуется в Maven — используется как `project(":speech")`. AAR отдельно не самодостаточен: `sherpa-onnx` подключён как `implementation`, плюс нужны assets.

## Устройство модуля

```text
speech/src/main/java/com/example/speech/
├── api/            публичный контракт: Transcriber, StreamingTranscriber, события, конфиг
├── impl/           SherpaTranscriber, SherpaStreamingTranscriber, StreamingChunkProcessor,
│                   SherpaModelFactory, AsrRunner, ReleaseGate, LruCache, ConfigKeys
├── audio/          WavReader, VadSegmenter, SherpaVadEngine, OnlineSpeakerClusterer,
│                   SpeakerChangeDetector, ClusterPruner
└── data/           ModelRegistry, BundledModels (internal)
```

- `SherpaTranscriber` держит `OfflineRecognizer` и кеш `OfflineSpeakerDiarization`; `Mutex` сериализует расшифровки, а `ReleaseGate` откладывает `release()` до конца текущей. Для `expectedSpeakers` диаризатор берётся из LRU по числу кластеров (два варианта).
- `SherpaStreamingTranscriber` держит `OfflineRecognizer`, VAD и `SpeakerEmbeddingExtractor`; ASR идёт в отдельной корутине через очередь на 8 чанков. Нарезка по смене говорящего, распознавание куска и назначение спикера вынесены в `StreamingChunkProcessor` — он проверяется JVM-тестом без native-моделей.
- `AsrRunner` режет длинные сегменты на куски до 45 с, ища тихую границу в последних 2 с.
- `WavReader.open` отдаёт файл кусками (так читает `SherpaVadTranscriber`, память не зависит от длины), `WavReader.readMono16k` — целиком в `FloatArray` (batch); поддерживается только канонический WAV (см. «Входной WAV»).
- `SpeakerReclusterer` кеширует сходства кластеров; на сессии больше 600 реплик (час-полтора речи) реплики сначала кластеризуются блоками подряд идущих, по сходству, а не по онлайн-меткам.

## Ограничения

- **Только русский язык** (GigaAM-v3). Смешанная речь и другие языки — вне поддержки.
- **Только 16 кГц, моно, PCM16.** Batch принимает лишь канонический WAV: `RIFF/WAVE`, `fmt ` 16 байт на смещении 12, `data` сразу на смещении 36, без дополнительных чанков. Максимум 60 минут для batch и 12 часов для `SherpaVadTranscriber`.
- Batch читает весь WAV в память: 60 минут — это ~230 МБ Java heap сверх native-памяти моделей. Для длинных записей — `SherpaVadTranscriber`: он читает файл кусками. Проверено на записи 76 минут.
- Streaming не даёт промежуточный текст: только готовые реплики. Таймкоды — на уровне реплик (границы считаются по меткам времени слов GigaAM, сами слова наружу не отдаются).
- Перекрывающаяся речь и шумная запись ухудшают диаризацию. В streaming число говорящих ограничено `maxOnlineSpeakers` (по умолчанию 20); в batch `expectedSpeakers` принимает 1..20, а авто-режим жёсткого потолка не имеет.
- Нет встроенного скачивания моделей, foreground-сервиса, разрешений, UI, БД и экспорта.
- Один экземпляр транскрибера на процесс. Batch и streaming одновременно — это две копии моделей и ~2× память; лучше последовательно.
- `release()` обязателен: нативный `OfflineRecognizer`/диаризатор не освобождаются сборщиком мусора.

## Производительность

Ориентир по бенчмарку на эмуляторе (x86_64, 6 ядер, Android 16 / API 36, 4 потока, аудио 19,75 с, 2 говорящих) — [`bench-results/`](../bench-results/). RTF — отношение времени обработки к длительности аудио, меньше — лучше.

| Режим | Загрузка моделей | Время | RTF | PSS |
|---|---:|---:|---:|---:|
| Batch | 5,7 с | 4,8 с | 0,24 | 475 МБ |
| Streaming | 2,1 с | 2,3 с | 0,12 | 443 МБ |

На телефоне цифры другие; перед релизом бенчмарк стоит снять на целевом устройстве. Планировать ~500 МБ PSS и 5–10 с на первую загрузку моделей.

## Интеграция в приложение

Короткая версия:

1. `implementation(project(":speech"))` + `noCompress += listOf("onnx", "txt")`.
2. Веса в `speech/src/main/assets/`, прогнать `:speech:verifyBundledModels`.
3. Hilt передаёт один экземпляр `SherpaVadTranscriber` в очередь приложения.
4. `load()` один раз на пачку, `release()` в конце, события в БД.
5. Пройти [чек-лист](#чек-лист) на arm64-устройстве.

### Подключение

В корневом `settings.gradle.kts` уже есть:

```kotlin
include(":speech")

dependencyResolutionManagement {
    repositories {
        maven { url = uri("speech/local-repo") } // sherpa-onnx 1.13.0
    }
}
```

В `app/build.gradle.kts`:

```kotlin
android {
    androidResources {
        // Обязательно: sherpa-onnx читает .onnx из assets нативно,
        // сжатые assets он открыть не может.
        noCompress += listOf("onnx", "txt")
    }
}

dependencies {
    implementation(project(":speech"))
    // Публичный API возвращает Flow; корутины должны быть в compile classpath.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
```

Если приложение живёт в другом репозитории, `speech/` переносится целиком вместе с `speech/local-repo/`. Собранного AAR недостаточно: `sherpa-onnx` — `implementation`-зависимость, плюс нужны assets.

Требования: `minSdk >= 29`, JDK 17, Android SDK 37. Сборка приложения — `arm64-v8a` (debug и release). `:speech` сам не задаёт `abiFilters`, поэтому его инструментальные тесты и бенчмарк идут на x86_64-эмуляторе. Но `:app` режет ABI через `abiFilters += "arm64-v8a"` — на x86_64-эмуляторе такое приложение не запустится, нужен arm64-эмулятор или arm64-устройство.

В release-сборке `:app` включена оптимизация R8. Модуль `:speech` передаёт правила сохранения JNI-классов из `consumer-rules.pro`.

### Подключение движка через Hilt

`SherpaVadTranscriber` реализует `Transcriber` для готовых WAV и `StreamingTranscriber` для живого PCM. Обе точки входа используют один набор загруженных моделей. Приложение читает PCM из растущего WAV для живого режима, а в отложенном режиме и при импорте передаёт готовый WAV. `Utterance` и `SegmentReady` отдают реплики с предварительными номерами спикеров. В `Done` приходит итоговый список, который контроллер сохраняет через `replaceSegments`.

Веса читаются из assets APK. `FakeTranscriber` доступен в тестовых наборах `:app`. Нативный движок проверяется инструментальными тестами на устройстве или совместимом эмуляторе.

Модуль Hilt создаёт `SherpaVadTranscriber` с `@ApplicationContext` и областью `@Singleton`.

### Очередь и отмена

`DefaultProcessingController` записывает `SegmentReady` через `appendSegment`, `Done` через `replaceSegments` и переводит `Failed` в `TRANSCRIPTION_FAILED`. Hilt предоставляет экранам и сервису общий контроллер, который создаётся при инъекции в `Application`.

Для очереди `load()` вызывается **один раз на пачку** заданий, `release()` — когда очередь опустела и в `finally` работника. Не загружать модели на каждое задание: холодная загрузка — это 5–10 с и лишняя нагрузка на память.

```kotlin
val job = scope.launch {
    transcriber.transcribe(wav).collect { event -> /* ... */ }
}

// Пользователь нажал «Отменить»:
job.cancelAndJoin()          // отменит коллектор; Flow завершится CancellationException
// release() — только после этого, в finally работника
```

- `CancellationException` не оборачивать в `Failed`/`TRANSCRIPTION_FAILED` — пробрасывать.
- `job.cancelAndJoin()` может ждать завершения текущего native-шага (см. раздел про отмену).
- После отмены освободить WAV, если он был создан этим заданием, и очистить частичные сегменты — это уже делает контроллер.

### Ошибки

| Где | Сигнал | Что делать в приложении |
|---|---|---|
| `load()` | исключение | `TRANSCRIPTION_FAILED`; в лог — stack trace и текст |
| `transcribe()` | `Failed(cause)` | `TRANSCRIPTION_FAILED`, частичные сегменты сохранить |
| `transcribe()` | `CancellationException` | пробросить, статус `CANCELLED` |
| Поток завершился без `Done` | нет события | считать ошибкой |
| Неверный WAV | `Failed(IllegalArgumentException)` | ошибка конвертации, не показывать как «ML сломался» |
| Нет моделей | `load()` → `IllegalStateException` | ошибка сборки/упаковки, не retry-loop |

Не пытаться автоматически повторять `load()`/`transcribe()` при `IllegalStateException`: без исправления APK это не поможет.

### Память и процесс

- **Память.** Планировать ~450–500 МБ PSS на один загруженный экземпляр. Пик batch — плюс WAV в `FloatArray` (~230 МБ на 60 минут); `SherpaVadTranscriber` читает кусками: на записи 76 минут PSS 570–700 МБ, как на коротких. Не держать два экземпляра и не загружать модели «на всякий случай»: `release()` при простое.
- **`largeHeap` не обязателен** и не помогает native-памяти; поможет только один экземпляр, короткие файлы и `release()`.
- **Потоки.** `numThreads = 4` — разумный дефолт. Больше потоков не уменьшают память и могут ухудшить отзывчивость; VAD всегда на 1 потоке.
- **Главный поток.** `load()` main-safe, события можно собирать в scope приложения. Но не запускать несколько расшифровок и не делать `release()` во время работы.
- **Процесс.** На время задания нужен foreground-сервис и `PARTIAL_WAKE_LOCK` — это делает приложение, не `:speech`. Иначе система убьёт процесс или усыпит CPU на длинной записи.
- **Смерть процесса.** Незавершённые сессии приложение помечает `INTERRUPTED` и даёт retry — это уже реализовано в контроллере. `:speech` ничего не персистит.

### Диагностика

| Симптом | Причина | Решение |
|---|---|---|
| `UnsatisfiedLinkError` / `dlopen` | Не та ABI или сборка без native-libs | `arm64-v8a` на устройстве; не вырезать `jni/`; проверить `abiFilters` |
| `IllegalStateException: В APK отсутствуют модели` | Не распакованы assets | Скачать zip в `speech/src/main/assets`, `:speech:verifyBundledModels` |
| `FileNotFoundException` на `assets/models/...` | Assets сжаты | `noCompress += listOf("onnx", "txt")` в `:app` |
| `WAV должен быть...` / `Это не WAV` | Неканонический вход | Прогнать через `AudioConverter.toWav()` |
| `Аудио длиннее заданного лимита` | 60 мин в batch, 12 ч в `SherpaVadTranscriber` | Разбить файл или использовать `SherpaVadTranscriber` |
| `Модели не загружены` | Нет `load()` | Вызвать `load()` до `transcribe()` |
| Пустой текст, мало сегментов | Тишина, не русская речь, слишком короткие фразы | Проверить аудио; `minSpeechMs` только для streaming |
| Слишком много/мало говорящих | Порог кластеризации | `expectedSpeakers` (batch), `autoClusterThreshold` (batch-авто), `onlineClusterThreshold` (streaming) |
| OOM / падение native | Длинный файл + несколько экземпляров | Один экземпляр, `release()`, короче файл |
| Долгая отмена | Идёт native-диаризация | Ждать завершения шага; UI «Останавливаем…» |

Логи: по умолчанию `:speech` в logcat не пишет. Подробный лог streaming включается `adb shell setprop log.tag.Speech DEBUG` и читается `adb logcat -s Speech:D` — тайминги VAD/ASR/embedding, разрезы и назначение спикеров, без распознанного текста (подробнее в [`BENCHMARK.md`](BENCHMARK.md#диагностика-в-logcat)). Для разбора присылать stack trace, модель устройства, длительность и формат аудио, `numThreads`, вывод бенчмарка и этот лог.

### Чек-лист

Сборка и упаковка:

- [ ] `settings.gradle.kts` содержит `:speech` и `speech/local-repo`.
- [ ] `implementation(project(":speech"))` в `:app`.
- [ ] `noCompress += listOf("onnx", "txt")` в `:app`.
- [ ] `:speech:verifyBundledModels` — `OK (8 файлов)`.
- [ ] `:app:assembleDebug` собирается, APK устанавливается на arm64-устройство.
- [x] Hilt предоставляет один `SherpaVadTranscriber`, `FakeTranscriber` доступен в тестовых наборах.

Поведение:

- [ ] Импорт WAV с двумя говорящими → 2 спикера, осмысленный текст, таймкоды по возрастанию.
- [ ] Два задания в очереди → `load()` вызван один раз, `release()` — после опустошения очереди.
- [ ] Отмена во время распознавания → `CANCELLED`, частичные сегменты очищены, следующий job работает.
- [ ] `Failed` → `FAILED`/`TRANSCRIPTION_FAILED`, частичные сегменты сохранены.
- [ ] Запись на 1–1,5 часа через `SherpaVadTranscriber` → `DONE`, память не растёт с длиной.
- [ ] Файл длиннее лимита (12 ч `SherpaVadTranscriber`, 60 мин batch) → понятная ошибка, без падения.
- [ ] Экран выключен 2 минуты во время задания → обработка доходит до `DONE`.
- [ ] Убийство процесса в середине задания → после перезапуска `INTERRUPTED`, retry работает.
- [ ] После `release()` повторный `load()` + расшифровка работают (нет «залипшей» native-памяти).
- [ ] Память: один экземпляр, PSS в пределах ожидаемого, нет роста между заданиями.

Если пункт не проходит — это либо упаковка (assets/ABI), либо контракт (порядок `load`/`release`), либо качество аудио. Сначала стоит проверить таблицу диагностики.

Если API не хватает — лучше обсудить изменение контракта, чем патчить модуль по месту.
