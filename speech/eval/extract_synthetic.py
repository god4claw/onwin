"""Extract one row from the synthetic Russian diarization parquet.

Writes canonical 16 kHz mono PCM16 WAV plus words/turns JSONL and RTTM.
Usage: python extract_synthetic.py <parquet> <out_dir> [row_index]
"""
from __future__ import annotations

import json
import sys
import wave
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq


def write_wav(path: Path, samples: np.ndarray, rate: int = 16000) -> None:
    pcm = np.clip(np.round(samples * 32768.0), -32768, 32767).astype("<i2").tobytes()
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(rate)
        wf.writeframes(pcm)


def main() -> None:
    if len(sys.argv) not in (3, 4):
        raise SystemExit(__doc__)
    src = Path(sys.argv[1])
    out = Path(sys.argv[2])
    index = int(sys.argv[3]) if len(sys.argv) == 4 else 0
    out.mkdir(parents=True, exist_ok=True)

    table = pq.read_table(src)
    row = table.slice(index, 1).to_pylist()[0]
    audio = row["audio"]
    samples = np.asarray(audio["array"], dtype=np.float32)
    rate = int(audio["sampling_rate"])
    if rate != 16000:
        raise SystemExit(f"expected 16 kHz, got {rate}")
    stem = f"synthetic_{index:05d}"
    write_wav(out / f"{stem}.wav", samples, rate)

    speakers = row["speakers"]
    if isinstance(speakers, str):
        speakers = json.loads(speakers)
    with (out / f"{stem}.turns.jsonl").open("w", encoding="utf-8") as f:
        for s in speakers:
            f.write(
                json.dumps(
                    {
                        "speaker": int(s["speaker_id"]),
                        "start": float(s["start"]),
                        "end": float(s["end"]),
                        "text": s["text"],
                    },
                    ensure_ascii=False,
                )
                + "\n"
            )
    with (out / f"{stem}.rttm").open("w", encoding="utf-8") as f:
        for s in speakers:
            start = float(s["start"])
            dur = float(s["end"]) - start
            f.write(
                f"SPEAKER {stem} 1 {start:.3f} {dur:.3f} <NA> <NA> "
                f"spk{int(s['speaker_id'])} <NA> <NA>\n"
            )
    print(
        f"{stem}: {len(samples) / rate:.2f}s, {row['num_speakers']} speakers, "
        f"{len(speakers)} turns, overlap={row['has_overlaps']}, noise={row['has_noise']}"
    )
    print(f"wrote {out / (stem + '.wav')}")


if __name__ == "__main__":
    main()
