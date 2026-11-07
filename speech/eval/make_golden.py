"""Build the golden set for streaming tests from one synthetic parquet shard.

Rows were picked from train-00000-of-00030.parquet of
ivkond/synthetic-speech-diarization-ru (MIT) to cover the cases that matter
for the app; duplicates in the shard were skipped:

  syn00_dialog2     row 0   dialogue, 2 speakers, clean, overlaps     46 s
  syn31_interview2  row 31  interview, 2 speakers, easy, no overlaps  35 s
  syn26_simult2     row 26  dialogue, 2 speakers, simultaneous speech 60 s
  syn17_noise_white row 17  interview, 2 speakers, white noise 13 dB  63 s
  syn62_noise_cafe  row 62  dialogue, 2 speakers, cafe noise 10 dB    35 s
  syn25_group3      row 25  group, 3 speakers, clean                  34 s
  syn35_group4      row 35  group, 4 speakers, crowd noise, hard      57 s
  syn45_mono1       row 45  monologue, 1 speaker, clean               36 s

Plus golden_long: all of the above back to back with 1.5 s of silence, ~6 min.
It checks that streaming keeps up over time (lag must not grow). Speaker
labels in its RTTM are prefixed per source file, but TTS voices may repeat
across files, so use it for WER/latency, not DER.

Writes <out>/<name>.wav (canonical 16 kHz mono PCM16), <name>.turns.jsonl
(speaker/start/end/text) and <name>.rttm.

Usage: python make_golden.py <parquet> <out_dir> [--holdout]
Needs numpy and pyarrow.

--holdout writes a separate held-out set (other rows of the same shard, no
golden_long) for checking that parameters tuned on the golden set generalize:
never tune on it.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq

from extract_synthetic import write_wav

RATE = 16_000
GAP_SEC = 1.5

GOLDEN = [
    ("syn00_dialog2", 0),
    ("syn31_interview2", 31),
    ("syn26_simult2", 26),
    ("syn17_noise_white", 17),
    ("syn62_noise_cafe", 62),
    ("syn25_group3", 25),
    ("syn35_group4", 35),
    ("syn45_mono1", 45),
]

# Other rows of the same shard, duplicates skipped: 2-4 speakers, clean/noisy,
# overlaps and simultaneous speech, two monologues.
HOLDOUT = [
    ("hold03_interview2", 3), ("hold04_interview2", 4), ("hold08_simult2", 8),
    ("hold09_dialog2", 9), ("hold13_static2", 13), ("hold24_dialog2", 24),
    ("hold29_dialog2", 29), ("hold30_group3_noise", 30), ("hold32_group4_crowd", 32),
    ("hold33_hum2", 33), ("hold39_white2", 39), ("hold41_hum2", 41),
    ("hold44_simult_static2", 44), ("hold51_pink2", 51), ("hold59_static2", 59),
    ("hold64_pink2", 64), ("hold20_mono1_hum", 20), ("hold43_mono1_brown", 43),
]


def write_refs(out: Path, name: str, turns: list[dict]) -> None:
    with (out / f"{name}.turns.jsonl").open("w", encoding="utf-8") as f:
        for t in turns:
            f.write(json.dumps(t, ensure_ascii=False) + "\n")
    with (out / f"{name}.rttm").open("w", encoding="utf-8") as f:
        for t in turns:
            dur = t["end"] - t["start"]
            if dur > 0:
                f.write(
                    f"SPEAKER {name} 1 {t['start']:.3f} {dur:.3f} <NA> <NA> "
                    f"{t['speaker']} <NA> <NA>\n"
                )


def main() -> None:
    args = [a for a in sys.argv[1:] if a != "--holdout"]
    holdout = "--holdout" in sys.argv[1:]
    if len(args) != 2:
        raise SystemExit(__doc__)
    src = Path(args[0])
    out = Path(args[1])
    out.mkdir(parents=True, exist_ok=True)

    table = pq.read_table(src)
    long_audio: list[np.ndarray] = []
    long_turns: list[dict] = []
    offset = 0.0

    for name, index in (HOLDOUT if holdout else GOLDEN):
        row = table.slice(index, 1).to_pylist()[0]
        if int(row["audio"]["sampling_rate"]) != RATE:
            raise SystemExit(f"row {index}: expected {RATE} Hz")
        samples = np.asarray(row["audio"]["array"], dtype=np.float32)
        speakers = row["speakers"]
        if isinstance(speakers, str):
            speakers = json.loads(speakers)
        turns = [
            {
                "speaker": f"spk{int(s['speaker_id'])}",
                "start": float(s["start"]),
                "end": float(s["end"]),
                "text": s["text"],
            }
            for s in sorted(speakers, key=lambda s: float(s["start"]))
        ]
        write_wav(out / f"{name}.wav", samples, RATE)
        write_refs(out, name, turns)
        print(
            f"{name:18} row={index:2d} {len(samples) / RATE:5.1f}s "
            f"speakers={row['num_speakers']} turns={len(turns)} "
            f"type={row['conversation_type']} noise={row['noise_type']}"
        )

        long_audio.append(samples)
        long_turns.extend(
            {**t, "speaker": f"{name}:{t['speaker']}", "start": t["start"] + offset, "end": t["end"] + offset}
            for t in turns
        )
        gap = np.zeros(int(GAP_SEC * RATE), dtype=np.float32)
        long_audio.append(gap)
        offset += (len(samples) + len(gap)) / RATE

    if holdout:
        return
    long = np.concatenate(long_audio[:-1])
    write_wav(out / "golden_long.wav", long, RATE)
    write_refs(out, "golden_long", long_turns)
    print(f"{'golden_long':18} {len(long) / RATE:6.1f}s turns={len(long_turns)}")


if __name__ == "__main__":
    main()
