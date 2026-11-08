# Декодер: beam search и базовая точка WER

Дата: 2026-11-07. Ветка `llm-onwin`. Стенд: `Pixel_8_API36`, Android 16 (API 36),
x86_64, 4 ядра, 3,8 ГБ; `threads=4`, `chunkMs=100`, `pace=0`, остальное —
дефолты `SpeechConfig` (VAD 0,35/450, AGC -20 дБ, ERes2Net, recluster 0,65).

## Beam search для GigaAM невозможен

Команда:

```powershell
.\speech\scripts\benchmark.ps1 -Wav ...\synthetic_00040.wav -Mode streaming `
    -Runs 1 -Pace 0 -Decoding beam -MaxActivePaths 4
```

Результат — падение процесса:

```
W sherpa-onnx: .../csrc/offline-recognizer-ctc-impl.h:Init:208
W sherpa-onnx: Only greedy_search is supported at present. Given modified_beam_search
INSTRUMENTATION_RESULT: shortMsg=Process crashed.
```

Это нативный `abort`, а не игнорирование параметра: sherpa-onnx 1.13.0
поддерживает для NeMo-CTC только `greedy_search`. Следствия:

- WER beam search не меняет — сравнивать нечего, прогона нет;
- **hotwords тоже недоступны**: они работают только вместе с beam search;
- единственный доступный рычаг WER на текущей модели — сам ASR, VAD/AGC и
  диаризация, но не декодер.

В коде это закрыто тем, что ручки декодера нет вовсе: `SherpaModelFactory` всегда
передаёт `greedy_search`. Конфиг, который на CTC-модели роняет процесс, а на RNN-T
не проверен ни одним прогоном, хуже, чем его отсутствие. Для RNN-T
(`AsrModel.E2E_RNNT`, модель по умолчанию) `modified_beam_search` реализован — beam
можно вернуть отдельной задачей вместе с замером WER и RTF.

## Базовая точка: greedy, 7 файлов золотого набора

`score_wer.py`, режим streaming, run 1. Спикер-независимо, как в скрипте.

| Файл | WER | S | D | I | N |
|---|---:|---:|---:|---:|---:|
| synthetic_00000 | 12,07 % | 2 | 5 | 0 | 58 |
| synthetic_00001 | 2,50 % | 0 | 1 | 0 | 40 |
| synthetic_00008 | 40,96 % | 22 | 11 | 1 | 83 |
| synthetic_00010 | 18,18 % | 7 | 5 | 0 | 66 |
| synthetic_00025 | 9,62 % | 1 | 0 | 4 | 52 |
| synthetic_00032 | 7,14 % | 1 | 0 | 2 | 42 |
| synthetic_00040 | 13,89 % | 3 | 2 | 0 | 36 |
| **Итого** | **17,77 %** | 36 | 24 | 7 | 377 |

С `bench-golden-baseline.md` (16,0 %) напрямую не сравнивать: там 8
разговоров плюс `golden_long` (6 мин), здесь — 7 файлов без монолога и без
склейки, другой прогон и один run. Совпадение по порядку величины (16–18 %)
говорит, что пайплайн не деградировал; `synthetic_00008` — файл с шумом, он и
тянет общий WER вверх (40,96 % при 83 словах эталона).

## Что это значит

1. Beam search из списка улучшений вычеркнут — не по вкусу, а потому что
   падает.
2. Hotwords вычеркнуты вместе с ним.
3. Если beam нужен принципиально — менять тип акустической модели на
   zipformer-CTC (в assets лежит `streaming/zipformer_ru_*.onnx`), но это другая
   модель и другой WER: сначала замер, потом решение.
