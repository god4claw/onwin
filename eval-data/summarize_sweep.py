"""Compact threshold-sweep report for SpeechBenchmarkTest JSONs."""
from __future__ import annotations

import json
import sys
from collections import defaultdict
from pathlib import Path


def ref_speakers(refs_dir: Path, label: str) -> int | None:
    path = refs_dir / f"{label}.rttm"
    if not path.is_file():
        return None
    return len({line.split()[7] for line in path.read_text(encoding="utf-8").splitlines() if line.strip()})


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: summarize_sweep.py <sweep_dir> <refs_dir>")
    sweep = Path(sys.argv[1])
    refs = Path(sys.argv[2])
    groups: dict[tuple[str, float], list[tuple[str, int, int]]] = defaultdict(list)

    for path in sorted(sweep.rglob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        label = data["label"]
        ref = ref_speakers(refs, label)
        if ref is None:
            continue
        for mode in ("batch", "streaming"):
            section = data.get(mode)
            if not section or not section.get("runs"):
                continue
            threshold = float(data["config"][f"{'auto' if mode == 'batch' else 'online'}ClusterThreshold"])
            hyp = int(section["runs"][0]["speakerCount"])
            groups[(mode, threshold)].append((label, ref, hyp))

    for (mode, threshold), rows in sorted(groups.items()):
        exact = sum(1 for _, ref, hyp in rows if ref == hyp)
        err = sum(abs(ref - hyp) for _, ref, hyp in rows)
        print(f"\n== {mode}, threshold={threshold:.2f}: exact {exact}/{len(rows)}, |speaker error|={err}")
        for label, ref, hyp in rows:
            mark = "OK" if ref == hyp else "  "
            print(f"  {mark} {label:18} GT={ref} hyp={hyp}")


if __name__ == "__main__":
    main()
