"""One table for a set of SpeechBenchmarkTest runs: quality + latency + cost.

For every result JSON it looks up <refs>/<label>.turns.jsonl (WER) and
<refs>/<label>.rttm (DER, the small frame scorer from summarize_bench.py:
no collar, overlap skipped). Only run 1 of each mode is scored.

Columns:
  WER      speaker-agnostic word error rate (score_wer.py normalization)
  DER      rough frame DER; "-" when there is no RTTM or >6 speakers.
           Synthetic references are slot-based (turns run back to back and
           cover the pauses), so their DER is inflated by "missed speech";
           look at spkErr there
  spkErr   wrong-speaker share of the time where both reference and
           hypothesis have exactly one active speaker: pure attribution
           error, independent of how the reference marks pauses
  spk      reference / hypothesis speaker count
  lat50/90/max  utterance latency: text arrived minus end of that speech in
           the audio feed. Meaningful only with pace=1 (real-time feed)
  tsStart/tsEnd  timestamp error: median |start| and |end| difference, s,
           between each reference turn and the hypothesis segment that
           overlaps it most (synthetic references: comparative only)
  lag      max delay of the feed behind real time (pace>0): audio a live
           microphone would have to buffer. Should be ~0
  drain    time from the last audio chunk to Done
  rtf      total processing time / audio duration

Usage:
  python stream_report.py <result.json|dir>... --refs <golden_dir> [--markdown]
No third-party dependencies.
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
from pathlib import Path

from score_wer import WerCounts, read_reference, score as score_wer
from summarize_bench import STEP, labels_at, read_rttm, score as score_der

MAX_DER_SPEAKERS = 6
COLUMNS = ["label", "mode", "pace", "WER", "DER", "spkErr", "tsStart", "tsEnd", "spk", "lat50", "lat90", "latMax", "lag", "drain", "rtf", "pss"]


def collect(paths: list[Path]) -> list[Path]:
    files: list[Path] = []
    for path in paths:
        if path.is_dir():
            files.extend(p for p in sorted(path.glob("*.json")) if p.name != "latest.json")
        else:
            files.append(path)
    return files


def speaker_error(ref: list, segments: list[dict], mapping: dict) -> float | None:
    hyp = [(s["startMs"] / 1000, s["endMs"] / 1000, str(s["speaker"])) for s in segments]
    duration = max([e for _, e, _ in ref] + [e for _, e, _ in hyp] + [0.0])
    frames = int(round(duration / STEP)) + 1
    both = wrong = 0
    for ref_set, hyp_set in zip(labels_at(ref, frames, STEP), labels_at(hyp, frames, STEP)):
        if len(ref_set) == 1 and len(hyp_set) == 1:
            both += 1
            if mapping.get(next(iter(hyp_set))) != next(iter(ref_set)):
                wrong += 1
    return wrong / both if both else None


def timestamp_errors(ref: list, segments: list[dict]) -> tuple[float | None, float | None]:
    starts, ends = [], []
    for a, b, _ in ref:
        best, overlap = None, 0.0
        for s in segments:
            o = min(b, s["endMs"] / 1000) - max(a, s["startMs"] / 1000)
            if o > overlap:
                best, overlap = s, o
        if best is not None:
            starts.append(abs(best["startMs"] / 1000 - a))
            ends.append(abs(best["endMs"] / 1000 - b))
    if not starts:
        return None, None
    return statistics.median(starts), statistics.median(ends)


def seconds(ms: float | None) -> str:
    return "-" if ms is None else f"{ms / 1000:.2f}"


def row_for(path: Path, refs: Path, mode: str) -> tuple[dict, WerCounts | None, dict] | None:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        if mode == "streaming":  # предупредить один раз, а не на каждый режим
            print(f"skip {path}: not a benchmark JSON ({e})", file=sys.stderr)
        return None
    section = data.get(mode)
    if not section or not section.get("runs"):
        return None
    run = section["runs"][0]
    label = data.get("label", path.stem)
    config = data.get("config", {})
    segments = sorted(run.get("segmentList", []), key=lambda s: s["startMs"])

    wer_counts = None
    turns = refs / f"{label}.turns.jsonl"
    if turns.is_file():
        wer_counts, _ = score_wer(read_reference(turns), " ".join(s.get("text", "") for s in segments))

    der = None
    spk_err = None
    ts_start = ts_end = None
    ref_speakers = None
    rttm = refs / f"{label}.rttm"
    hyp_speakers = len({s["speaker"] for s in segments})
    if rttm.is_file():
        ref = read_rttm(rttm)
        ref_speakers = len({spk for _, _, spk in ref})
        ts_start, ts_end = timestamp_errors(ref, segments)
        if ref_speakers <= MAX_DER_SPEAKERS and hyp_speakers <= MAX_DER_SPEAKERS and segments:
            scored = score_der(ref, segments)
            der = scored["der"]
            spk_err = speaker_error(ref, segments, scored["mapping"])

    streaming = mode == "streaming"
    row = {
        "label": label,
        "mode": mode,
        "pace": str(config.get("pace", 0)) if streaming else "-",
        "WER": "-" if wer_counts is None else f"{wer_counts.wer * 100:.1f}%",
        "DER": "-" if der is None else f"{der * 100:.1f}%",
        "spkErr": "-" if spk_err is None else f"{spk_err * 100:.1f}%",
        "tsStart": "-" if ts_start is None else f"{ts_start:.2f}",
        "tsEnd": "-" if ts_end is None else f"{ts_end:.2f}",
        "spk": f"{ref_speakers if ref_speakers is not None else '?'}/{hyp_speakers}",
        "lat50": seconds(run.get("latencyP50Ms")) if streaming else "-",
        "lat90": seconds(run.get("latencyP90Ms")) if streaming else "-",
        "latMax": seconds(run.get("latencyMaxMs")) if streaming else "-",
        "lag": seconds(run.get("maxFeedLagMs")) if streaming else "-",
        "drain": seconds(run.get("drainMs")) if streaming else "-",
        "rtf": f"{run.get('rtf', 0):.3f}",
        "pss": str(run.get("pssMb", "-")),
    }
    raw = {
        "lat50": run.get("latencyP50Ms"),
        "lat90": run.get("latencyP90Ms"),
        "latMax": run.get("latencyMaxMs"),
        "lag": run.get("maxFeedLagMs"),
        "rtf": run.get("rtf"),
        "der": der,
        "spkErr": spk_err,
        "tsStart": ts_start,
        "tsEnd": ts_end,
        "spkOk": ref_speakers is not None and ref_speakers == hyp_speakers,
        "failed": False,
    }
    return row, wer_counts, raw


def summary_row(mode: str, rows: list[tuple[dict, WerCounts | None, dict]]) -> dict:
    pooled = sum((c for _, c, _ in rows if c is not None), WerCounts())
    raws = [r for _, _, r in rows]

    def med(key: str) -> float | None:
        values = [r[key] for r in raws if r.get(key) is not None]
        return statistics.median(values) if values else None

    def worst(key: str) -> float | None:
        values = [r[key] for r in raws if r.get(key) is not None]
        return max(values) if values else None

    streaming = mode == "streaming"
    der = med("der")
    spk_err = med("spkErr")
    ts_start = med("tsStart")
    ts_end = med("tsEnd")
    spk_ok = sum(1 for r in raws if r.get("spkOk"))
    rtf = med("rtf")
    return {
        "label": "ALL (pooled WER, median/max)",
        "mode": mode,
        "pace": "",
        "WER": f"{pooled.wer * 100:.1f}%" if pooled.ref_words else "-",
        "DER": "-" if der is None else f"{der * 100:.1f}%",
        "spkErr": "-" if spk_err is None else f"{spk_err * 100:.1f}%",
        "tsStart": "-" if ts_start is None else f"{ts_start:.2f}",
        "tsEnd": "-" if ts_end is None else f"{ts_end:.2f}",
        "spk": f"{spk_ok}/{len(raws)} ok",
        "lat50": seconds(med("lat50")) if streaming else "-",
        "lat90": seconds(med("lat90")) if streaming else "-",
        "latMax": seconds(worst("latMax")) if streaming else "-",
        "lag": seconds(worst("lag")) if streaming else "-",
        "drain": "",
        "rtf": "-" if rtf is None else f"{rtf:.3f}",
        "pss": "",
    }


def render(rows: list[dict], markdown: bool) -> str:
    if markdown:
        lines = ["| " + " | ".join(COLUMNS) + " |", "|" + "---|" * len(COLUMNS)]
        lines += ["| " + " | ".join(r[c] for c in COLUMNS) + " |" for r in rows]
        return "\n".join(lines)
    widths = {c: max(len(c), *(len(r[c]) for r in rows)) for c in COLUMNS}
    lines = ["  ".join(c.ljust(widths[c]) for c in COLUMNS)]
    lines += ["  ".join(r[c].ljust(widths[c]) for c in COLUMNS) for r in rows]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("results", nargs="+", type=Path)
    parser.add_argument("--refs", required=True, type=Path)
    parser.add_argument("--markdown", action="store_true")
    args = parser.parse_args()

    files = collect(args.results)
    table: list[dict] = []
    for mode in ("streaming", "batch", "vad"):
        rows = [r for r in (row_for(f, args.refs, mode) for f in files) if r is not None]
        if not rows:
            continue
        table += [row for row, _, _ in rows]
        if len(rows) > 1:
            table.append(summary_row(mode, rows))
    if not table:
        raise SystemExit("no benchmark runs found")
    print(render(table, args.markdown))


if __name__ == "__main__":
    main()
