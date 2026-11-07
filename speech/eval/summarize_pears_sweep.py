"""Threshold sweep report for the Pear dev windows (DER + speaker count)."""
from __future__ import annotations

import json
import sys
from collections import defaultdict
from pathlib import Path

from pyannote.core import Annotation, Segment
from pyannote.metrics.diarization import DiarizationErrorRate


def read_rttm(path: Path, uri: str) -> Annotation:
    ann = Annotation(uri=uri)
    for line in path.read_text(encoding="utf-8").splitlines():
        p = line.split()
        if len(p) >= 8 and p[0] == "SPEAKER":
            s = float(p[3])
            ann[Segment(s, s + float(p[4])), p[7]] = p[7]
    return ann


def read_pred(path: Path, mode: str, uri: str) -> tuple[Annotation, float]:
    d = json.loads(path.read_text(encoding="utf-8"))
    run = d[mode]["runs"][0]
    ann = Annotation(uri=uri)
    end = float(d["audio"]["durationMs"]) / 1000.0
    for s in run["segmentList"]:
        a = s["startMs"] / 1000.0
        b = s["endMs"] / 1000.0
        if b > a:
            ann[Segment(a, b), str(s["speaker"])] = str(s["speaker"])
    return ann, end


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: summarize_pears_sweep.py <pears_dev_dir> <refs_dir>")
    root = Path(sys.argv[1])
    refs = Path(sys.argv[2])
    metric = DiarizationErrorRate(collar=0.25)
    groups: dict[tuple[str, float], list[tuple[str, int, int, float]]] = defaultdict(list)

    for path in sorted(root.rglob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        label = data["label"]
        ref_path = refs / f"{label}.rttm"
        if not ref_path.is_file():
            continue
        ref = read_rttm(ref_path, label)
        ref_speakers = len(ref.labels())
        for mode in ("batch", "streaming"):
            if not data.get(mode):
                continue
            hyp, end = read_pred(path, mode, label)
            der = metric(ref, hyp, uem=Segment(0, end))
            threshold = float(
                data["config"]["autoClusterThreshold" if mode == "batch" else "onlineClusterThreshold"]
            )
            groups[(mode, threshold)].append((label, ref_speakers, len(hyp.labels()), der))

    for (mode, threshold), rows in sorted(groups.items()):
        exact = sum(1 for _, ref, hyp, _ in rows if ref == hyp)
        mean_der = sum(der for *_, der in rows) / len(rows)
        print(
            f"\n== {mode}, threshold={threshold:.2f}: exact {exact}/{len(rows)}, "
            f"mean DER={mean_der * 100:.2f}%"
        )
        for label, ref, hyp, der in rows:
            print(f"  {label:14} GT={ref} hyp={hyp} DER={der * 100:5.2f}%")


if __name__ == "__main__":
    main()
