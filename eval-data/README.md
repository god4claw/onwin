# Evaluation data and benchmark runs

Local-only evaluation harness for `:speech`. Nothing here is required by the app.

## What is here

```text
convert_wav.py             any PCM WAV -> canonical 16 kHz mono PCM16
prepare_pears_refs.py      Pear Chats TextGrid (UTF-16) -> words/turns/RTTM
extract_synthetic.py       one synthetic parquet row -> WAV + turns + RTTM
summarize_bench.py         benchmark JSON -> speaker-count/rough-DER summary
summarize_sweep.py         threshold sweep -> exact speaker-count table
score_der.py               official-ish DER via pyannote.metrics
download_ranges.py         parallel range downloader for slow hosts
complete_pears_parts.py    finish the missing Pear chunks with smaller ranges
assemble_pears.py          concatenate the 16 Pear chunks
make_pears_dev.py          cut 3-minute 3-speaker dev windows from Pear
summarize_pears_sweep.py   threshold sweep report with DER on Pear dev
```

## Datasets used

- **Pear Chats session 22** — real Russian 4-party dialogue, common 48 kHz stereo
  mic + per-speaker TextGrids. The download server is slow; a 33 s excerpt was
  used for the first run.
- **synthetic-speech-diarization-ru** — 2 000 synthetic Russian tracks with
  `speakers[] = {speaker_id,start,end,text}`, overlap/noise flags, MIT.
- `fixture_ru_dialogue.wav` — the repository's existing 46.8 s single-voice TTS
  fixture, used as a smoke test.

## Benchmark commands

```powershell
# build + install the androidTest APK once
powershell -NoProfile -ExecutionPolicy Bypass -File .\speech\scripts\benchmark.ps1 `
  -Wav .\fixture_ru_dialogue.wav -Mode both -Runs 1 -Threads 4 -Speakers auto

# subsequent runs (APK already installed)
powershell -NoProfile -ExecutionPolicy Bypass -File .\speech\scripts\benchmark.ps1 `
  -Wav .\eval-data\synthetic\extracted\synthetic_00000.wav `
  -Mode both -Runs 1 -Threads 4 -Speakers auto `
  -AutoThreshold 0.65 -OnlineThreshold 0.55 -NoBuild
```

The script uses the first connected adb device. Results are written to
`bench-results/<out-dir>/*.json` and `*.txt`.

## Host-side summary

```powershell
python .\eval-data\summarize_bench.py `
  .\bench-results\synthetic-run .\eval-data\synthetic\extracted
python .\eval-data\summarize_sweep.py `
  .\bench-results\sweep .\eval-data\synthetic\extracted
python .\eval-data\score_der.py `
  --ref .\eval-data\pears22\refs\pears22_full.rttm `
  --pred .\bench-results\pears-full\pears22_full-....json `
  --mode batch --collar 0.25
```

The DER in `summarize_bench.py` is a small 10 ms frame scorer: no collar, skips
frames with overlap, brute-forces the speaker mapping. `score_der.py` uses
`pyannote.metrics` and is the one to quote. Note that benchmark segments are
already filtered by non-empty ASR text, so this is end-to-end speaker-attributed
DER, not pure diarization DER.

## Current caveats

- Benchmark device is the `Pixel_8_API36` x86_64 emulator, not an arm64 phone.
- `-Speakers N` affects batch only; streaming uses `onlineClusterThreshold`.
- `auto_threshold`/`online_threshold`, `prune_min_ms`,
  `prune_min_fraction` and `speaker_change_threshold` are exposed by both
  benchmark scripts.
- Full Pear session 22 is assembled locally at
  `eval-data/pears22/pears22_full.wav` (1107.84 s, gitignored).
- All numbers are single-run smoke measurements unless a run directory says `runs=3`.
- `score_der.py` scores the emitted speaker-attributed segments. Segments with
  empty ASR text are dropped before the benchmark sees them, so this DER is
  end-to-end, not pure diarization.
