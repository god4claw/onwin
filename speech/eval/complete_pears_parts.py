"""Download the remaining 13.3 MB chunks of the Pear session in parallel.

The full-file downloader left 13/16 chunks complete; this script targets the
missing chunks and splits each one into smaller ranges, because the server
throttles a single connection to ~18 KB/s.
"""
from __future__ import annotations

import concurrent.futures
import sys
import time
import urllib.request
from pathlib import Path

URL = "http://multidiscourse.iling-ran.ru/pears22/Pears22W-au.wav"
TOTAL = 212_705_358
CHUNK = 13_294_085
MISSING = [0, 1, 9]
SUBRANGES = 8


def fetch_range(start: int, end: int, path: Path, attempts: int = 5) -> None:
    last: Exception | None = None
    for attempt in range(1, attempts + 1):
        try:
            req = urllib.request.Request(URL, headers={"Range": f"bytes={start}-{end}"})
            with urllib.request.urlopen(req, timeout=120) as resp:
                data = resp.read()
            expected = end - start + 1
            if len(data) != expected:
                raise IOError(f"short range {start}-{end}: {len(data)} != {expected}")
            path.write_bytes(data)
            return
        except Exception as exc:  # noqa: BLE001
            last = exc
            time.sleep(min(5 * attempt, 20))
    raise RuntimeError(f"range {start}-{end} failed: {last}")


def main() -> None:
    root = Path(sys.argv[1])
    root.mkdir(parents=True, exist_ok=True)
    jobs = []
    for part in MISSING:
        part_start = part * CHUNK
        part_end = min(TOTAL - 1, part_start + CHUNK - 1)
        part_len = part_end - part_start + 1
        step = (part_len + SUBRANGES - 1) // SUBRANGES
        pieces = []
        for i in range(SUBRANGES):
            a = part_start + i * step
            b = min(part_end, a + step - 1)
            if a <= b:
                piece = root / f".part-{part:03d}-{i:02d}"
                pieces.append((a, b, piece))
                jobs.append((a, b, piece))
        (root / f"part-{part:03d}.pieces").write_text(
            "\n".join(str(p) for _, _, p in pieces), encoding="utf-8"
        )

    print(f"downloading {len(jobs)} subranges for parts {MISSING}", flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(jobs)) as pool:
        futures = [pool.submit(fetch_range, a, b, p) for a, b, p in jobs]
        for i, future in enumerate(concurrent.futures.as_completed(futures), 1):
            future.result()
            print(f"  {i}/{len(jobs)} subranges done", flush=True)

    for part in MISSING:
        pieces = [
            Path(line)
            for line in (root / f"part-{part:03d}.pieces").read_text(encoding="utf-8").splitlines()
        ]
        out = root / f"part-{part:03d}"
        with out.open("wb") as dst:
            for piece in pieces:
                dst.write(piece.read_bytes())
        for piece in pieces:
            piece.unlink()
        (root / f"part-{part:03d}.pieces").unlink()
        expected = CHUNK if part != 15 else CHUNK - 2
        if out.stat().st_size != expected:
            raise SystemExit(f"{out}: {out.stat().st_size} != {expected}")
        print(f"assembled {out} ({out.stat().st_size} bytes)", flush=True)


if __name__ == "__main__":
    main()
