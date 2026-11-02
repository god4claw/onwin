"""Summarize SpeechBenchmarkTest JSONs and compute frame-level DER.

This is a deliberately small host-side scorer:
  * 10 ms frames
  * skipped frames where either reference or hypothesis has >1 active speaker
  * best speaker permutation (brute force, <=6 speakers)
  * no collar

It is good enough for comparing runs and spotting over/under-clustering,
but it is not a replacement for pyannote.metrics DER.

Usage:
  python summarize_bench.py <bench_results_dir> <refs_dir>
"""
from __future__ import annotations

import itertools
import json
import sys
from pathlib import Path

STEP = 0.01


def read_rttm(path: Path) -> list[tuple[float, float, str]]:
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) >= 8 and parts[0] == "SPEAKER":
            start, dur, spk = float(parts[3]), float(parts[4]), parts[7]
            out.append((start, start + dur, spk))
    return out


def labels_at(segments: list[tuple[float, float, str]], n: int, step: float) -> list[set[str]]:
    frames: list[set[str]] = [set() for _ in range(n)]
    for start, end, spk in segments:
        a = max(0, int(start / step))
        b = min(n, int(round(end / step)))
        for i in range(a, b):
            frames[i].add(spk)
    return frames


def best_mapping(ref_frames: list[set[str]], hyp_frames: list[set[str]], hyp_speakers: list[str], ref_speakers: list[str]):
    best = None
    # Map each hypothesis speaker to one reference speaker or None.
    choices = ref_speakers + [None]
    for combo in itertools.product(choices, repeat=len(hyp_speakers)):
        mapping = dict(zip(hyp_speakers, combo))
        correct = confusion = 0
        for ref, hyp in zip(ref_frames, hyp_frames):
            if len(ref) != 1 or len(hyp) != 1:
                continue
            mapped = mapping.get(next(iter(hyp)))
            if mapped == next(iter(ref)):
                correct += 1
            elif mapped is not None:
                confusion += 1
        score = correct - confusion
        if best is None or score > best[0]:
            best = (score, mapping, correct, confusion)
    assert best is not None
    return best[1]


def score(ref: list[tuple[float, float, str]], hyp: list[dict]) -> dict:
    hyp_segments = [(float(s["startMs"]) / 1000, float(s["endMs"]) / 1000, str(s["speaker"])) for s in hyp]
    duration = max([e for _, e, _ in ref] + [e for _, e, _ in hyp_segments] + [0.0])
    n = int(round(duration / STEP)) + 1
    ref_frames = labels_at(ref, n, STEP)
    hyp_frames = labels_at(hyp_segments, n, STEP)
    ref_speakers = sorted({s for _, _, s in ref})
    hyp_speakers = sorted({s for _, _, s in hyp_segments})
    mapping = best_mapping(ref_frames, hyp_frames, hyp_speakers, ref_speakers)

    miss = fa = conf = ref_speech = 0
    for ref_set, hyp_set in zip(ref_frames, hyp_frames):
        if ref_set:
            ref_speech += 1
        if len(ref_set) > 1 or len(hyp_set) > 1:
            continue  # keep the small scorer simple
        if ref_set and not hyp_set:
            miss += 1
        elif hyp_set and not ref_set:
            fa += 1
        elif ref_set and hyp_set:
            mapped = mapping.get(next(iter(hyp_set)))
            if mapped != next(iter(ref_set)):
                conf += 1
    denom = ref_speech or 1
    return {
        "der": (miss + fa + conf) / denom,
        "miss": miss / denom,
        "fa": fa / denom,
        "conf": conf / denom,
        "ref_speakers": len(ref_speakers),
        "hyp_speakers": len(hyp_speakers),
        "speaker_count_error": len(hyp_speakers) - len(ref_speakers),
        "mapping": mapping,
    }


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    results = Path(sys.argv[1])
    refs = Path(sys.argv[2])
    rows = []
    for path in sorted(results.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        label = data.get("label", path.stem)
        ref_path = refs / f"{label}.rttm"
        if not ref_path.is_file():
            continue
        ref = read_rttm(ref_path)
        for mode in ("batch", "streaming"):
            section = data.get(mode)
            if not section or not section.get("runs"):
                continue
            run = section["runs"][0]
            s = score(ref, run.get("segmentList", []))
            rows.append((label, mode, s, run))
    print(f"{'label':22} {'mode':9} {'DER':>7} {'miss':>6} {'FA':>6} {'conf':>6} {'ref':>3} {'hyp':>3} {'dSpk':>5}")
    for label, mode, s, run in rows:
        print(
            f"{label:22} {mode:9} {s['der']*100:6.1f}% {s['miss']*100:5.1f}% "
            f"{s['fa']*100:5.1f}% {s['conf']*100:5.1f}% {s['ref_speakers']:3d} "
            f"{s['hyp_speakers']:3d} {s['speaker_count_error']:+5d}"
        )


if __name__ == "__main__":
    main()
