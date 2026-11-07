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
make_golden.py             golden set for streaming tests from one synthetic shard
score_wer.py               WER of a benchmark JSON against turns.jsonl / txt
stream_report.py           one table: WER, DER, speaker error, latency, lag, RTF
run_golden.sh              benchmark.sh over the whole golden set + report
recluster_sim.py           host copy of SpeakerReclusterer: tune on --dump-embeddings JSONs
make_long.py               concatenate set WAVs (+ refs, block map) into one long recording
score_long.py              WER per block + speaker error with one mapping for a long recording
```

## Datasets used

- **Pear Chats session 22** — real Russian 4-party dialogue, common 48 kHz stereo
  mic + per-speaker TextGrids. The download server is slow; a 33 s excerpt was
  used for the first run.
- **synthetic-speech-diarization-ru** — 2 000 synthetic Russian tracks with
  `speakers[] = {speaker_id,start,end,text}`, overlap/noise flags, MIT.
- `fixture_ru_dialogue.wav` — the repository's existing 46.8 s single-voice TTS
  fixture, used as a smoke test.

## Golden set (streaming)

Eight short synthetic conversations picked to cover what the app meets:
2-speaker dialogue and interview, simultaneous speech, white and cafe noise,
3- and 4-speaker groups, a monologue; plus `golden_long` (~6 min, all of them
back to back) to check that streaming keeps up over time. The list and the
reasons are in the `make_golden.py` docstring.

```bash
# one-time: a 77 MB shard of ivkond/synthetic-speech-diarization-ru (MIT)
mkdir -p speech/eval/synthetic
curl -L -o speech/eval/synthetic/train-00000-of-00030.parquet \
  https://huggingface.co/datasets/ivkond/synthetic-speech-diarization-ru/resolve/main/train-00000-of-00030.parquet
python -m venv .venv-eval && .venv-eval/bin/pip install numpy pyarrow
.venv-eval/bin/python speech/eval/make_golden.py \
  speech/eval/synthetic/train-00000-of-00030.parquet speech/eval/golden

# run (emulator or phone connected); --pace 1 = real-time feed, measures latency
bash speech/eval/run_golden.sh bench-results/golden/paced --mode streaming --pace 1 --runs 1
# re-print the table later
python3 speech/eval/stream_report.py bench-results/golden/paced --refs speech/eval/golden
```

**Held-out set.** `make_golden.py <parquet> speech/eval/holdout --holdout` writes
18 other conversations from the same shard. Tune parameters on the golden set,
then check them on the held-out set **without re-tuning** — the speaker
re-clustering threshold was validated this way (golden-tuned 0.60 gave the same
gain on held-out data).

**Tuning diarization on the host.** Run the benchmark with `--dump-embeddings`
(no need for `--pace`: segmentation does not depend on it), then sweep in
seconds instead of rebuilding the APK:

```bash
bash speech/eval/run_golden.sh bench-results/emb --mode streaming --runs 1 --dump-embeddings
python speech/eval/recluster_sim.py bench-results/emb --refs speech/eval/golden --grid
```

WAV, `turns.jsonl` and `rttm` files are gitignored; only the scripts are in git.
`score_wer.py`, `stream_report.py` and `run_golden.sh` need plain Python 3, no packages.

Reading the table:

- **WER** is speaker-agnostic, after normalization (lower case, ё→е, no
  punctuation). The synthetic references spell numbers out and GigaAM writes
  digits ("десять" vs "10"), so absolute WER is a bit pessimistic; comparisons
  between configs are still fair. `score_wer.py --diff` shows the alignment.
- **DER** on synthetic references is inflated: their turns run back to back
  and cover pauses, so silence counts as missed speech. Use **spkErr** there —
  the wrong-speaker share of the time where both sides have one speaker.
  DER is meaningful on Pear Chats, whose turns come from word timestamps.
- **lat50/lat90/latMax** — from the end of a phrase in the audio feed to its
  text; only meaningful with `--pace 1`.
- **lag** — how far the feed fell behind real time, i.e. audio a live
  microphone would have to buffer. Anything above ~0 means ASR does not keep up.

## Benchmark commands

```powershell
# build + install the androidTest APK once
powershell -NoProfile -ExecutionPolicy Bypass -File .\speech\scripts\benchmark.ps1 `
  -Wav .\fixture_ru_dialogue.wav -Mode both -Runs 1 -Threads 4 -Speakers auto

# subsequent runs (APK already installed)
powershell -NoProfile -ExecutionPolicy Bypass -File .\speech\scripts\benchmark.ps1 `
  -Wav .\speech\eval\synthetic\extracted\synthetic_00000.wav `
  -Mode both -Runs 1 -Threads 4 -Speakers auto `
  -AutoThreshold 0.65 -OnlineThreshold 0.55 -NoBuild
```

The script uses the first connected adb device. Results are written to
`bench-results/<out-dir>/*.json` and `*.txt`.

## Host-side summary

```powershell
python .\speech\eval\summarize_bench.py `
  .\bench-results\synthetic-run .\speech\eval\synthetic\extracted
python .\speech\eval\summarize_sweep.py `
  .\bench-results\sweep .\speech\eval\synthetic\extracted
python .\speech\eval\score_der.py `
  --ref .\speech\eval\pears22\refs\pears22_full.rttm `
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
  `speech/eval/pears22/pears22_full.wav` (1107.84 s, gitignored).
- All numbers are single-run smoke measurements unless a run directory says `runs=3`.
- `score_der.py` scores the emitted speaker-attributed segments. Segments with
  empty ASR text are dropped before the benchmark sees them, so this DER is
  end-to-end, not pure diarization.
