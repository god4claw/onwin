"""Parallel HTTP range downloader for the slow Pear Chats server.

Usage:
  python download_ranges.py <url> <out_file> [workers]

The server advertises Accept-Ranges; each worker fetches a contiguous byte
range, then the parts are concatenated and the final size is verified.
"""
from __future__ import annotations

import concurrent.futures
import sys
import time
import urllib.request
from pathlib import Path


def head_size(url: str) -> int:
    req = urllib.request.Request(url, method="HEAD")
    with urllib.request.urlopen(req, timeout=60) as resp:
        return int(resp.headers["Content-Length"])


def fetch(url: str, start: int, end: int, path: Path, attempts: int = 5) -> None:
    last: Exception | None = None
    for attempt in range(1, attempts + 1):
        try:
            req = urllib.request.Request(url, headers={"Range": f"bytes={start}-{end}"})
            with urllib.request.urlopen(req, timeout=120) as resp:
                data = resp.read()
            expected = end - start + 1
            if len(data) != expected:
                raise IOError(f"short range {start}-{end}: {len(data)} != {expected}")
            path.write_bytes(data)
            return
        except Exception as exc:  # noqa: BLE001 - retry all transient failures
            last = exc
            time.sleep(min(5 * attempt, 20))
    raise RuntimeError(f"range {start}-{end} failed: {last}")


def main() -> None:
    if len(sys.argv) not in (3, 4):
        raise SystemExit(__doc__)
    url = sys.argv[1]
    out = Path(sys.argv[2])
    workers = int(sys.argv[3]) if len(sys.argv) == 4 else 12
    total = head_size(url)
    out.parent.mkdir(parents=True, exist_ok=True)
    part_dir = out.with_suffix(out.suffix + ".parts")
    part_dir.mkdir(exist_ok=True)

    chunk = (total + workers - 1) // workers
    jobs = []
    for i in range(workers):
        start = i * chunk
        end = min(total - 1, start + chunk - 1)
        if start <= end:
            jobs.append((i, start, end, part_dir / f"part-{i:03d}"))
    print(f"{url}: {total} bytes, {len(jobs)} ranges, workers={workers}", flush=True)

    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [
            pool.submit(fetch, url, start, end, path)
            for _, start, end, path in jobs
        ]
        for i, future in enumerate(concurrent.futures.as_completed(futures)):
            future.result()
            print(f"  {i + 1}/{len(jobs)} ranges done", flush=True)

    with out.open("wb") as dst:
        for _, _, _, path in jobs:
            dst.write(path.read_bytes())
    size = out.stat().st_size
    if size != total:
        raise SystemExit(f"size mismatch: {size} != {total}")
    for _, _, _, path in jobs:
        path.unlink()
    part_dir.rmdir()
    print(f"wrote {out} ({size} bytes)", flush=True)


if __name__ == "__main__":
    main()
