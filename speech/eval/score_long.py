"""Метрики длинной записи из make_long.py по JSON бенчмарка (режим vad).

Выравнивание слов всей 8-часовой записи разом в чистом Python не досчитается,
поэтому WER считается по блокам исходных файлов: реплика гипотезы относится к
блоку, в который попадает её середина. Атрибуция спикеров — по кадрам 100 мс
всей записи с одним отображением спикеров на всю запись (как и должно быть:
номер спикера обязан держаться все часы).

  python speech/eval/score_long.py bench-results/long/long_8h-*.json --refs speech/eval/long
"""
import argparse
import itertools
import json
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from score_wer import WerCounts, score  # noqa: E402

FRAME = 0.1


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("result", type=Path)
    ap.add_argument("--refs", required=True, type=Path)
    a = ap.parse_args()

    data = json.loads(a.result.read_text(encoding="utf-8"))
    label = data["label"]
    run = data["vad"]["runs"][0]
    segments = sorted(run["segmentList"], key=lambda s: s["startMs"])
    blocks = json.loads((a.refs / f"{label}.blocks.json").read_text(encoding="utf-8"))
    turns = [json.loads(line) for line in (a.refs / f"{label}.turns.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]

    # WER по блокам
    hyp_by_block = [[] for _ in blocks]
    ref_by_block = [[] for _ in blocks]
    starts = [b["start"] for b in blocks]

    def block_of(t: float) -> int:
        lo, hi = 0, len(starts)
        while lo < hi:
            mid = (lo + hi) // 2
            if starts[mid] <= t:
                lo = mid + 1
            else:
                hi = mid
        return max(0, lo - 1)

    for s in segments:
        hyp_by_block[block_of((s["startMs"] + s["endMs"]) / 2000)].append(s.get("text", ""))
    for t in sorted(turns, key=lambda t: t["start"]):
        ref_by_block[block_of((t["start"] + t["end"]) / 2)].append(t["text"])
    total = WerCounts()
    per_round = {}
    for b, ref, hyp in zip(blocks, ref_by_block, hyp_by_block):
        c, _ = score(" ".join(ref), " ".join(hyp))
        total = total + c
        per_round[b["round"]] = per_round.get(b["round"], WerCounts()) + c

    # Атрибуция по кадрам с одним отображением на всю запись
    end = max(blocks[-1]["end"], max((s["endMs"] for s in segments), default=0) / 1000)
    n = int(end / FRAME) + 1
    ref_frames = [None] * n
    for line in (a.refs / f"{label}.rttm").read_text(encoding="utf-8").splitlines():
        p = line.split()
        s0, d, spk = float(p[3]), float(p[4]), p[7]
        for i in range(int(s0 / FRAME), min(n, int((s0 + d) / FRAME))):
            ref_frames[i] = spk if ref_frames[i] in (None, spk) else "#overlap"
    hyp_frames = [None] * n
    for s in segments:
        for i in range(int(s["startMs"] / 1000 / FRAME), min(n, int(s["endMs"] / 1000 / FRAME))):
            hyp_frames[i] = s["speaker"]
    pairs = Counter((r, h) for r, h in zip(ref_frames, hyp_frames) if r not in (None, "#overlap") and h is not None)
    ref_spk = sorted({r for r, _ in pairs})
    hyp_spk = sorted({h for _, h in pairs})
    both = sum(pairs.values())
    best = 0
    # Отображение «спикер гипотезы → спикер эталона» один к одному; лишние кластеры — ошибка.
    for perm in itertools.permutations(hyp_spk, min(len(ref_spk), len(hyp_spk))):
        best = max(best, sum(pairs[(r, h)] for r, h in zip(ref_spk, perm)))
    spk_err = 1 - best / both if both else float("nan")

    audio_h = data["audio"]["durationMs"] / 3_600_000
    print(f"{label}: {audio_h:.2f} ч аудио, {len(segments)} реплик")
    print(f"  WER {total.wer * 100:.2f}% (S {total.substitutions} / D {total.deletions} / I {total.insertions} из {total.ref_words})")
    print("  WER по кругам: " + ", ".join(f"{k + 1}: {v.wer * 100:.2f}%" for k, v in sorted(per_round.items())))
    print(f"  спикеров: эталон {len(ref_spk)}, найдено {len({s['speaker'] for s in segments})}; "
          f"ошибка атрибуции (одно отображение на всю запись) {spk_err * 100:.1f}%")
    print(f"  RTF {run.get('rtf', 0):.3f}, время {run.get('totalMs', 0) / 60_000:.1f} мин, PSS в конце {run.get('pssMb')} МБ")


if __name__ == "__main__":
    main()
