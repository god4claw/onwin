"""Concatenate the 16 completed Pear session chunks and verify the size."""
from __future__ import annotations

import sys
import wave
from pathlib import Path

TOTAL = 212_705_358
CHUNK = 13_294_085


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    parts = Path(sys.argv[1])
    out = Path(sys.argv[2])
    with out.open("wb") as dst:
        for i in range(16):
            path = parts / f"part-{i:03d}"
            expected = CHUNK if i < 15 else CHUNK - 2
            actual = path.stat().st_size
            if actual != expected:
                raise SystemExit(f"{path}: {actual} != {expected}")
            dst.write(path.read_bytes())
    if out.stat().st_size != TOTAL:
        raise SystemExit(f"{out}: {out.stat().st_size} != {TOTAL}")
    with wave.open(str(out), "rb") as wf:
        print(
            f"{out}: {out.stat().st_size} bytes, "
            f"{wf.getnchannels()}ch {wf.getframerate()}Hz "
            f"{wf.getsampwidth() * 8}-bit, "
            f"{wf.getnframes() / wf.getframerate():.1f}s",
            flush=True,
        )


if __name__ == "__main__":
    main()
