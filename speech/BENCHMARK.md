# Headless-бенчмарк `:speech`

Замеряет batch- и streaming-режимы на устройстве или эмуляторе и сохраняет JSON + текстовую таблицу в `bench-results/`.

## Запуск

PowerShell:

```powershell
.\speech\scripts\benchmark.ps1 -Wav .\sample.wav -Mode both -Runs 3 -Threads 4 `
    -AutoThreshold 0.7 -OnlineThreshold 0.5
```

bash:

```bash
./speech/scripts/benchmark.sh --wav sample.wav --mode both --runs 3 --threads 4 \
    --auto-threshold 0.7 --online-threshold 0.5
```

Скрипт собирает и устанавливает `:speech:installDebugAndroidTest`, заливает WAV через adb, запускает `SpeechBenchmarkTest` и забирает результаты на хост.

## Хранилище на устройстве

WAV и результаты лежат в **internal storage** приложения:
`/data/user/0/com.example.speech.test/files/bench/`. Файл заливается через
`adb push` во `/data/local/tmp`, затем копируется через `run-as`, поэтому сразу
получает uid приложения. Результаты забираются `exec-out run-as cat` (в сырых
байтах, чтобы не побить UTF-8 в русском тексте).

Так сделано потому, что путь `/sdcard/Android/data/<pkg>/` на Android 11+ —
это FUSE-представление scoped storage. `adb push` создаёт там файл от `shell`,
и приложение его через `File()` не видит: тест падает с
`IllegalArgumentException: WAV не найден на устройстве`, хотя файл физически на
месте. Не возвращайте `push` в `/sdcard` — это регрессия.

`run-as` требует debuggable-сборки. `installDebugAndroidTest` ставит именно такую;
при `-NoBuild` на недебаггабельном APK скрипт упадёт с явной ошибкой.

## Вход

- WAV: 16 кГц, моно, PCM16, канонический 44-байтовый заголовок.
- Подключённое устройство или эмулятор с `adb`.
- Эмулятор x86_64 подходит для `:speech`; release-APK `:app` собран только под `arm64-v8a`.

## Параметры

| PowerShell | bash | По умолчанию | Описание |
|---|---|---|---|
| `-Wav` | `--wav` | — | Входной WAV |
| `-Mode` | `--mode` | `batch` | `batch`, `streaming` или `both` |
| `-Speakers` | `--speakers` | `auto` | `auto` или число спикеров для batch |
| `-Threads` | `--threads` | `4` | Потоки ASR |
| `-Runs` | `--runs` | `3` | Повторов на режим |
| `-ChunkMs` | `--chunk-ms` | `100` | Размер streaming-чанка, мс |
| `-AutoThreshold` | `--auto-threshold` | `0.80` | Порог batch-автокластеризации, `0..1` |
| `-OnlineThreshold` | `--online-threshold` | `0.45` | Порог streaming-кластеризации, `0..1` |
| `-PruneMinMs` | `--prune-min-ms` | `3000` | Минимальная длительность кластера после pruning, мс |
| `-PruneMinFraction` | `--prune-min-fraction` | `0.05` | Минимальная доля речи для кластера, `0..1` |
| `-SpeakerChangeThreshold` | `--speaker-change-threshold` | `0.65` | Порог косинуса для смены говорящего, `0..1` |
| `-Serial` | `--serial` | первый | Серийный номер adb-устройства |
| `-OutDir` | `--out` | `bench-results` | Каталог результатов |
| `-NoBuild` | `--no-build` | — | Не собирать и не устанавливать APK |
| `-Pace` | `--pace` | `0` | Скорость подачи в streaming: `0` — без пауз (пропускная способность), `1` — реальное время, как живой микрофон (задержки) |
| `-VadThreshold` | `--vad-threshold` | `VadConfig` | Порог Silero VAD, `0..1` |
| `-VadMinSilenceMs` | `--vad-min-silence-ms` | `VadConfig` (450) | Тишина, после которой реплика закрывается |
| `-VadMinSpeechMs` | `--vad-min-speech-ms` | `VadConfig` (250) | Минимальная длина речи |
| `-VadMaxSpeechMs` | `--vad-max-speech-ms` | `VadConfig` (25000) | Принудительный разрез длинной реплики |
| `-Agc` | `--agc` | `AgcConfig` (`on`) | АРУ перед VAD в streaming: `on` / `off` |
| `-AgcTargetDb` | `--agc-target-db` | `AgcConfig` (−20) | Целевой уровень речи, dBFS |
| `-AgcMaxGainDb` | `--agc-max-gain-db` | `AgcConfig` (24) | Потолок усиления, дБ |
| `-ReclusterThreshold` | `--recluster-threshold` | `SpeechConfig` (0.60) | Порог финальной перекластеризации спикеров в streaming, `0..1` |
| `-DumpEmbeddings` | `--dump-embeddings` | выкл. | Писать embedding каждой streaming-реплики в JSON (`segmentEmbeddings`) — для подбора кластеризации на хосте `speech/eval/recluster_sim.py` |

VAD- и АРУ-параметры по умолчанию не передаются: тест берёт значения из `VadConfig` и `AgcConfig`, чтобы у дефолтов был один источник.

## Задержка streaming

Без `--pace` аудио подаётся так быстро, как его принимает транскрайбер: это меряет
пропускную способность, но не то, что увидит пользователь. С `--pace 1` чанки
уходят по расписанию реального времени, как из микрофона, и в отчёт пишутся:

| Поле | Что это |
|---|---|
| `latencyP50Ms`, `latencyP90Ms`, `latencyMaxMs` | Задержка реплики: момент, когда пришёл `Utterance`, минус момент, когда в транскрайбер ушёл кусок аудио с концом этой реплики. Включает тишину `minSilenceMs`, ожидание в очереди, ASR и embedding |
| `maxFeedLagMs` | Насколько подача отставала от реального времени. Это аудио, которое живому микрофону пришлось бы буферизовать; должно быть около нуля. Если растёт — распознавание не успевает за речью |
| `drainMs` | Время от последнего чанка до `Done` |
| `events` | Журнал `speech` / `utterance` с `atMs`, таймкодами, спикером и `latencyMs` (без текста — он в `segmentList`) |

При `--pace 1` прогон длится столько же, сколько аудио.

## Золотой набор

`speech/eval/run_golden.sh` прогоняет benchmark по всем файлам набора и печатает
сводку `stream_report.py` (WER, DER, ошибка атрибуции спикера, задержки, RTF, PSS):

```bash
bash speech/eval/run_golden.sh bench-results/golden/paced --mode streaming --pace 1 --runs 1
bash speech/eval/run_golden.sh bench-results/golden/sil300 --no-build --mode streaming --pace 1 \
    --runs 1 --vad-min-silence-ms 300
```

Как собрать набор и как читать метрики — [`eval/README.md`](eval/README.md).

## Диагностика в logcat

```bash
adb shell setprop log.tag.Speech DEBUG   # включить, действует до перезагрузки
adb logcat -s Speech:D
adb shell setprop log.tag.Speech INFO    # выключить
```

`SherpaStreamingTranscriber` пишет по строке на VAD-чанк (`pending` — сколько
чанков ждёт ASR, `gain` — текущее усиление АРУ), разрез по смене спикера, каждую реплику (`asr`, `emb` в мс,
`spk`, `sim` — косинус с кластером, `new`, `via` — как выбран спикер) и итог
сессии. Распознанный текст в лог не пишется — только длина (`chars`).

## Результат

- `bench-results/<label>-<timestamp>.json`
- `bench-results/<label>-<timestamp>.txt`
- `latest.json` и `latest.txt` в каталоге результатов.

JSON (`schemaVersion` 2) содержит `generatedAt`, `label`, `mode`, `audio`, `config`, `device`, `batch`, `streaming`. В `config` пишутся `threads`, `runs`, `speakers`, `chunkMs`, `autoClusterThreshold`, `onlineClusterThreshold`, `clusterPruneMinMs`, `clusterPruneMinFraction`, `speakerChangeThreshold`, `pace`, `vad` и `agc` (поле `agc` добавлено без смены версии схемы: старые JSON без него читаются как «АРУ не было»). Для каждого режима есть `modelLoadMs`, список `runs` и `summary` с медианами: `medianTotalMs`, `medianRtf`, `medianSpeedX`, `medianPssMb`, `medianSpeakerCount`, `medianSegments`; у streaming ещё `medianLatencyP50Ms`, `medianLatencyP90Ms`, `medianLatencyMaxMs`, `medianMaxFeedLagMs`. Версия 2 только добавляет поля, скрипты для версии 1 продолжают работать.

Таблица показывает по каждому прогону время, RTF, скорость, PSS, число спикеров и сегментов; у streaming — ещё `lat50`/`lat90`/`latMax` и `lag`.
