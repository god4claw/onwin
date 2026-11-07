"""Host-side copy of SpeakerReclusterer for tuning on dumped embeddings.

Run the benchmark with --dump-embeddings, then try parameters here in seconds
instead of rebuilding the APK. The algorithm mirrors
speech/src/main/java/com/example/speech/impl/SpeakerReclusterer.kt; keep them
in sync.

Usage:
  python recluster_sim.py <dir with JSONs> --refs <dir with .rttm> [--threshold 0.6]
  python recluster_sim.py <dir> --refs <dir> --grid      # sweep the threshold

Prints speaker attribution error (spkErr, see stream_report.py) and speaker
count per file, for the current pipeline ("as run") and the simulation.
Tune on the golden set; check on the held-out set (make_golden.py --holdout)
without re-tuning. Needs numpy.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np

from stream_report import speaker_error
from summarize_bench import read_rttm, score as score_der


def recluster(records, threshold=0.60, min_cluster_ms=2500, keep_similarity=0.40, min_segments=2):
    with_emb = [i for i, r in enumerate(records) if r.get("embedding")]
    if not records:
        return []
    if not with_emb:
        return [0] * len(records)

    def dur_ms(i):
        return max(records[i]["endMs"] - records[i]["startMs"], 0)

    def weighted(i):
        v = np.asarray(records[i]["embedding"], dtype=np.float64)
        n = np.linalg.norm(v)
        return v * (math.sqrt(min(dur_ms(i) / 1000, 8.0)) / n) if n else v * 0

    def cos(a, b):
        d = np.linalg.norm(a) * np.linalg.norm(b)
        return float(a @ b / d) if d else 0.0

    clusters = [{"sum": weighted(i), "members": [i]} for i in with_emb]
    while len(clusters) > 1:
        best, pair = -2.0, None
        for a in range(len(clusters) - 1):
            for b in range(a + 1, len(clusters)):
                s = cos(clusters[a]["sum"], clusters[b]["sum"])
                if s > best:
                    best, pair = s, (a, b)
        if best < threshold:
            break
        a, b = pair
        clusters[a]["sum"] = clusters[a]["sum"] + clusters[b]["sum"]
        clusters[a]["members"] += clusters[b]["members"]
        del clusters[b]

    while len(clusters) > 1:
        absorbed = None
        for small in sorted(clusters, key=lambda c: sum(dur_ms(i) for i in c["members"])):
            if sum(dur_ms(i) for i in small["members"]) >= min_cluster_ms:
                continue
            nearest = max((c for c in clusters if c is not small), key=lambda c: cos(small["sum"], c["sum"]))
            if cos(small["sum"], nearest["sum"]) < keep_similarity and len(small["members"]) >= min_segments:
                continue
            nearest["sum"] = nearest["sum"] + small["sum"]
            nearest["members"] += small["members"]
            absorbed = small
            break
        if absorbed is None:
            break
        clusters = [c for c in clusters if c is not absorbed]

    label = {i: k for k, c in enumerate(clusters) for i in c["members"]}

    def center(i):
        return records[i]["startMs"] + records[i]["endMs"]

    raw = [label[i] if i in label else label[min(with_emb, key=lambda j: abs(center(j) - center(i)))]
           for i in range(len(records))]
    order: dict[int, int] = {}
    return [order.setdefault(x, len(order)) for x in raw]


def attribution(segments, ref):
    hyp = [{"startMs": s, "endMs": e, "speaker": spk} for s, e, spk in segments]
    ref_n, hyp_n = len({x[2] for x in ref}), len({h["speaker"] for h in hyp})
    if not hyp or ref_n > 6 or hyp_n > 6:
        return None, ref_n, hyp_n
    return speaker_error(ref, hyp, score_der(ref, hyp)["mapping"]), ref_n, hyp_n


def load(results: Path, refs: Path):
    for path in sorted(results.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        run = (data.get("streaming") or {}).get("runs", [{}])[0]
        rttm = refs / f"{data.get('label', path.stem)}.rttm"
        if "segmentEmbeddings" in run and rttm.is_file():
            yield data["label"], run, read_rttm(rttm)


def evaluate(files, threshold):
    errors, rows, ok = [], [], 0
    for label, run, ref in files:
        recs = run["segmentEmbeddings"]
        labels = recluster(recs, threshold) if threshold is not None else None
        segs = ([(r["startMs"], r["endMs"], l) for r, l in zip(recs, labels)] if labels is not None
                else [(s["startMs"], s["endMs"], s["speaker"]) for s in run["segmentList"]])
        err, ref_n, hyp_n = attribution(segs, ref)
        ok += ref_n == hyp_n
        if err is not None:
            errors.append(err)
        rows.append(f"  {label:24} spk {ref_n}/{hyp_n}  spkErr " + ("-" if err is None else f"{err * 100:5.1f}%"))
    return rows, errors, ok


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("results", type=Path)
    parser.add_argument("--refs", required=True, type=Path)
    parser.add_argument("--threshold", type=float, default=0.60)
    parser.add_argument("--grid", action="store_true")
    args = parser.parse_args()

    files = list(load(args.results, args.refs))
    if not files:
        raise SystemExit("no JSON with segmentEmbeddings (run the benchmark with --dump-embeddings)")
    for name, threshold in (("as run", None), (f"simulated threshold={args.threshold}", args.threshold)):
        rows, errors, ok = evaluate(files, threshold)
        print(f"{name}: spkErr mean {np.mean(errors) * 100:.1f}% median {np.median(errors) * 100:.1f}%, "
              f"speaker count right {ok}/{len(files)}")
        print("\n".join(rows))
    if args.grid:
        print("\nthreshold sweep:")
        for t in np.arange(0.40, 0.76, 0.05):
            _, errors, ok = evaluate(files, float(t))
            print(f"  {t:.2f}: spkErr mean {np.mean(errors) * 100:5.1f}% median {np.median(errors) * 100:5.1f}%  count {ok}/{len(files)}")


if __name__ == "__main__":
    main()
