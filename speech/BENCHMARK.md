# Headless-бенчмарк `:speech`

Замеряет batch- и streaming-режимы на устройстве или эмуляторе и сохраняет JSON + текстовую таблицу в `bench-results/`.

## Запуск

PowerShell:

```powershell
.\speech\scripts\benchmark.ps1 -Wav .\sample.wav -Mode both -Runs 3 -Threads 4
```

bash:

```bash
./speech/scripts/benchmark.sh --wav sample.wav --mode both --runs 3 --threads 4
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
| `-Serial` | `--serial` | первый | Серийный номер adb-устройства |
| `-OutDir` | `--out` | `bench-results` | Каталог результатов |
| `-NoBuild` | `--no-build` | — | Не собирать и не устанавливать APK |

## Результат

- `bench-results/<label>-<timestamp>.json`
- `bench-results/<label>-<timestamp>.txt`
- `latest.json` и `latest.txt` в каталоге результатов.

JSON содержит `schemaVersion`, `generatedAt`, `label`, `mode`, `audio`, `config`, `device`, `batch`, `streaming`. Для каждого режима есть `modelLoadMs`, список `runs` и `summary` с медианами: `medianTotalMs`, `medianRtf`, `medianSpeedX`, `medianPssMb`, `medianSpeakerCount`, `medianSegments`.

Таблица показывает по каждому прогону время, RTF, скорость, PSS, число спикеров и сегментов.
