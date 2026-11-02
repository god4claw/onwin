"""Score one SpeechBenchmarkTest JSON against an RTTM with pyannote.metrics.

Usage:
  python score_der.py --ref ref.rttm --pred result.json [--mode batch|streaming]
                      [--collar 0.25] [--skip-overlap]
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from pyannote.core import Annotation, Segment
from pyannote.metrics.diarization import DiarizationErrorRate


def read_rttm(path: Path, uri: str) -> Annotation:
    ann = Annotation(uri=uri)
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) < 8 or parts[0] != "SPEAKER":
            continue
        start, dur, speaker = float(parts[3]), float(parts[4]), parts[7]
        if dur <= 0:
            continue
        ann[Segment(start, start + dur), speaker] = speaker
    return ann


def read_pred(path: Path, mode: str, uri: str, run_index: int = 1) -> tuple[Annotation, float]:
    data = json.loads(path.read_text(encoding="utf-8"))
    section = data.get(mode)
    if not section or len(section.get("runs", [])) < run_index:
        raise SystemExit(f"no {mode} run {run_index} in {path}")
    run = section["runs"][run_index - 1]
    ann = Annotation(uri=uri)
    end = 0.0
    for seg in run.get("segmentList", []):
        start = float(seg["startMs"]) / 1000.0
        stop = float(seg["endMs"]) / 1000.0
        label = str(seg["speaker"])
        if stop <= start:
            continue
        ann[Segment(start, stop), label] = label
        end = max(end, stop)
    duration_ms = data.get("audio", {}).get("durationMs")
    if duration_ms:
        end = max(end, float(duration_ms) / 1000.0)
    return ann, end


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ref", required=True, type=Path)
    parser.add_argument("--pred", required=True, type=Path)
    parser.add_argument("--mode", default="batch", choices=["batch", "streaming"])
    parser.add_argument("--run", default=1, type=int, help="1-based run index")
    parser.add_argument("--collar", default=0.25, type=float)
    parser.add_argument("--skip-overlap", action="store_true")
    args = parser.parse_args()

    uri = args.pred.stem
    ref = read_rttm(args.ref, uri)
    hyp, duration = read_pred(args.pred, args.mode, uri, args.run)
    metric = DiarizationErrorRate(collar=args.collar, skip_overlap=args.skip_overlap)
    detailed = metric(ref, hyp, detailed=True, uem=Segment(0, max(duration, 1.0)))
    print(f"file: {args.pred}")
    print(f"mode: {args.mode}, run={args.run}, collar={args.collar}, skip_overlap={args.skip_overlap}")
    for key in ("diarization error rate", "missed detection", "false alarm", "confusion", "total", "correct"):
        value = detailed.get(key)
        if value is None:
            continue
        if key == "diarization error rate":
            print(f"{key:24}: {value * 100:.2f}%")
        else:
            print(f"{key:24}: {value:.2f}s")


if __name__ == "__main__":
    main()
