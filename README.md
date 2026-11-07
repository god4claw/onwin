# Goal

> Установка, веса и сборка — [`INSTALL.md`](INSTALL.md). Устройство движка — [`speech/README.md`](speech/README.md).

Разработать Android-приложение, которое локально преобразует аудиозаписи разговоров в текст, автоматически определяя говорящих и разделяя их реплики. Приложение должно формировать структурированную расшифровку диалога с указанием участников и временных меток.

# Progress

## :app
Каркас есть, реальный движок не подключён

`Готово:`

`Не готово:`

## :speech
Движок v2 готов; диаризация проверена на двух голосах

`Готово:`

- Batch-пайплайн: pyannote segmentation + CAM++ + FastClustering → ASR GigaAM-v3 по сегментам, expectedSpeakers 1..20, AsrRunner режет куски >45 с по тихой границе.
- Диаризация проверена: `two_speaker_dialogue` (19,75 с, два голоса) — 2 спикера в batch и streaming, метки 0/1 по порядку. На 46,8-с фикстуре один TTS-голос, поэтому `spk=1` там — правильный ответ.
- Streaming: Silero VAD (окно 512), SpeechStarted, реплика закрывается по 450 мс тишины или 25 с, ASR в отдельной корутине, очередь на 8 чанков.
- Спикеры в streaming уже размечаются — CAM++ embedding + OnlineSpeakerClusterer по косинусу, спикер приходит в Utterance.
- Веса в assets, INTERNET в манифесте нет, сетевой загрузки в модуле нет.
- Тесты: JVM на VAD и кластеризацию, instrumented на bundled-модели, batch-диаризацию и стриминг; headless-бенчмарк.
- Документация: speech/README.md, BENCHMARK.md.

`Не готово:`

- Цифр с arm64-телефона нет, только x86_64-эмулятор.
- WER/DER не измеряются: эталонных транскриптов нет, качество оценивается вручную.

# Problems

# Notes

## Стек :speech 
`это чисто on-device Android-библиотека на Kotlin без UI и сетевой зависимости в рантайме:`
- Платформа: Android Library (AGP 9.4.1, Kotlin 2.4.21), minSdk 29, compileSdk 37, Java 11.
- ASR/диаризация: нативный sherpa-onnx через JNI; AAR 1.13.0 (~38 МБ) лежит в локальном Maven-репозитории `local-repo` и подключается как `com.k2fsa.sherpa.onnx:sherpa-onnx:1.13.0`.
- Модели (все в `speech/src/main/assets`, читаются через `AssetManager`, без скачивания):
  - GigaAM-v3 e2e CTC int8 (~305 МБ) — русское ASR с пунктуацией;
  - CAM++ (~27 МБ) — speaker embeddings для диаризации;
  - pyannote segmentation int8 (~1.5 МБ) — диаризация;
  - Silero VAD (~0.6 МБ) — псевдостриминг;
  - словарь GigaAM (~2 КБ).

# Benchmark

Headless CLI: `speech/scripts/benchmark.ps1` и `speech/scripts/benchmark.sh`. Подробнее — [`speech/BENCHMARK.md`](speech/BENCHMARK.md).

Ключевой прогон на x86_64-эмуляторе: `two_speaker_dialogue` (19,75 с, два голоса) — batch 4,8 с (RTF 0,24), streaming 2,3 с (RTF 0,12), PSS 475/443 МБ, **2 спикера** в обоих режимах. На 46,8-с фикстуре один TTS-голос (F0 172–195 Гц, попарные косинусы ≤ 0,47), поэтому `spk=1` там корректен.

A/B-замеры рефакторинга v2 (112 прогонов, BEFORE/AFTER/AFTER/BEFORE): [`docs/bench-refactor-v2.md`](docs/bench-refactor-v2.md).

Псевдостриминг в реальном темпе на золотом наборе (задержки, WER, атрибуция спикеров, падение batch на монологе): [`docs/bench-golden-baseline.md`](docs/bench-golden-baseline.md).

АРУ перед VAD (дальний собеседник терялся VAD-ом): [`docs/bench-agc.md`](docs/bench-agc.md).

Финальная перекластеризация спикеров в streaming (короткие ответы сливались с собеседником): [`docs/bench-recluster.md`](docs/bench-recluster.md).

Расшифровка файлов: batch против VAD-пайплайна (`SherpaVadTranscriber` — точнее, вдвое быстрее, не падает): [`docs/bench-vad-transcriber.md`](docs/bench-vad-transcriber.md).

VAD в шуме: порог Silero 0,35, сравнение с TEN VAD, проверка на новой выборке: [`docs/bench-vad-noise.md`](docs/bench-vad-noise.md).

Метки времени слов: спикеры по словам, границы реплик, склейка ложных разрезов: [`docs/bench-word-timestamps.md`](docs/bench-word-timestamps.md).

ERes2Net вместо CAM++ для спикеров, набор живых диалогов: [`docs/bench-eres2net.md`](docs/bench-eres2net.md).

Где пайплайн теряет слова: ASR по всему VAD-чанку, разрез длинных сегментов в тишине: [`docs/bench-asr-edges.md`](docs/bench-asr-edges.md).

Снимки прогонов лежат локально в `bench-results/` и в git не входят; в репозитории — только A/B-результаты рефакторинга в `docs/bench/`.
