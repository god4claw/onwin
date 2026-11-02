"""Cut 3 short dev windows from the full Pear session, with RTTM refs."""
from __future__ import annotations

import sys
import wave
from pathlib import Path

import numpy as np

WINDOW_SEC = 180
STEP_SEC = 30


def read_rttm(path: Path) -> list[tuple[float, float, str]]:
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        p = line.split()
        if len(p) >= 8 and p[0] == "SPEAKER":
            s = float(p[3])
            out.append((s, s + float(p[4]), p[7]))
    return out


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    wav_path = Path(sys.argv[1])
    rttm_path = Path(sys.argv[2])
    out_dir = Path(sys.argv[3])
    out_dir.mkdir(parents=True, exist_ok=True)

    with wave.open(str(wav_path), "rb") as wf:
        rate = wf.getframerate()
        frames = wf.readframes(wf.getnframes())
    samples = np.frombuffer(frames, dtype="<i2")
    duration = samples.size / rate
    turns = read_rttm(rttm_path)

    candidates = []
    t = 0.0
    while t + WINDOW_SEC <= duration:
        inside = [x for x in turns if x[0] < t + WINDOW_SEC and x[1] > t]
        speakers = {x[2] for x in inside}
        speech = sum(max(0.0, min(x[1], t + WINDOW_SEC) - max(x[0], t)) for x in inside)
        if len(speakers) >= 3:
            candidates.append((len(speakers), speech, t))
        t += STEP_SEC
    candidates.sort(key=lambda x: (-x[0], -x[1], x[2]))

    chosen: list[float] = []
    for _, _, start in candidates:
        if all(abs(start - c) >= WINDOW_SEC for c in chosen):
            chosen.append(start)
        if len(chosen) == 3:
            break
    if not chosen:
        raise SystemExit("no 3-speaker windows found")

    for i, start in enumerate(chosen):
        a = int(round(start * rate))
        b = int(round((start + WINDOW_SEC) * rate))
        label = f"pears_dev_{i:02d}"
        with wave.open(str(out_dir / f"{label}.wav"), "wb") as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)
            wf.setframerate(rate)
            wf.writeframes(samples[a:b].tobytes())
        lines = []
        for s, e, spk in turns:
            if e <= start or s >= start + WINDOW_SEC:
                continue
            s2 = max(s, start) - start
            e2 = min(e, start + WINDOW_SEC) - start
            if e2 > s2:
                lines.append(
                    f"SPEAKER {label} 1 {s2:.3f} {e2 - s2:.3f} <NA> <NA> {spk} <NA> <NA>"
                )
        (out_dir / f"{label}.rttm").write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"{label}: {start:.1f}s..{start + WINDOW_SEC:.1f}s, {len(lines)} turns")


if __name__ == "__main__":
    main()
