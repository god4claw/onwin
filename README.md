# Goal

> Установка, веса и сборка — [`INSTALL.md`](INSTALL.md). Устройство движка — [`speech/README.md`](speech/README.md).

Разработать Android-приложение, которое локально преобразует аудиозаписи разговоров в текст, автоматически определяя говорящих и разделяя их реплики. Приложение должно формировать структурированную расшифровку диалога с указанием участников и временных меток.

# Progress

## :app
Каркас есть, реальный движок не подключён

`Готово:`

`Не готово:`

## :speech
В разработке, выбран стек

`Готово:`

`Не готово:`

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
