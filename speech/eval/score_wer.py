"""Word error rate of a SpeechBenchmarkTest JSON against a reference transcript.

Speaker-agnostic: hypothesis segments are joined in time order, reference
turns too. Both sides are normalized the same way: lower case, ё -> е,
everything except letters and digits becomes a space. Numbers are compared
as written, so "5" vs "пять" counts as an error; keep that in mind on
references with spelled-out numbers.

Usage:
  python score_wer.py --ref ref.turns.jsonl|ref.txt --pred result.json
                      [--mode streaming|batch] [--run 1] [--diff]
No third-party dependencies.
"""
from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from pathlib import Path

_NON_WORD = re.compile(r"[^0-9a-zа-я]+")


def normalize(text: str) -> list[str]:
    text = text.lower().replace("ё", "е")
    return _NON_WORD.sub(" ", text).split()


@dataclass(frozen=True)
class WerCounts:
    substitutions: int = 0
    deletions: int = 0
    insertions: int = 0
    ref_words: int = 0

    @property
    def errors(self) -> int:
        return self.substitutions + self.deletions + self.insertions

    @property
    def wer(self) -> float:
        return self.errors / self.ref_words if self.ref_words else 0.0

    def __add__(self, other: "WerCounts") -> "WerCounts":
        return WerCounts(
            self.substitutions + other.substitutions,
            self.deletions + other.deletions,
            self.insertions + other.insertions,
            self.ref_words + other.ref_words,
        )


def align(ref: list[str], hyp: list[str]) -> list[tuple[str, str | None, str | None]]:
    """Levenshtein alignment: ("ok"|"sub"|"del"|"ins", ref_word, hyp_word)."""
    rows, cols = len(ref) + 1, len(hyp) + 1
    cost = [[0] * cols for _ in range(rows)]
    for i in range(rows):
        cost[i][0] = i
    for j in range(cols):
        cost[0][j] = j
    for i in range(1, rows):
        for j in range(1, cols):
            same = ref[i - 1] == hyp[j - 1]
            cost[i][j] = min(
                cost[i - 1][j - 1] + (0 if same else 1),
                cost[i - 1][j] + 1,
                cost[i][j - 1] + 1,
            )
    ops = []
    i, j = len(ref), len(hyp)
    while i > 0 or j > 0:
        if i > 0 and j > 0 and cost[i][j] == cost[i - 1][j - 1] + (ref[i - 1] != hyp[j - 1]):
            ops.append(("ok" if ref[i - 1] == hyp[j - 1] else "sub", ref[i - 1], hyp[j - 1]))
            i, j = i - 1, j - 1
        elif i > 0 and cost[i][j] == cost[i - 1][j] + 1:
            ops.append(("del", ref[i - 1], None))
            i -= 1
        else:
            ops.append(("ins", None, hyp[j - 1]))
            j -= 1
    ops.reverse()
    return ops


def count(ops: list[tuple[str, str | None, str | None]]) -> WerCounts:
    kinds = [op for op, _, _ in ops]
    return WerCounts(
        substitutions=kinds.count("sub"),
        deletions=kinds.count("del"),
        insertions=kinds.count("ins"),
        ref_words=sum(1 for k in kinds if k != "ins"),
    )


def read_reference(path: Path) -> str:
    if path.suffix == ".jsonl":
        turns = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
        turns.sort(key=lambda t: float(t.get("start", 0.0)))
        return " ".join(str(t.get("text", "")) for t in turns)
    return path.read_text(encoding="utf-8")


def read_hypothesis(data: dict, mode: str, run_index: int) -> str:
    section = data.get(mode)
    if not section or len(section.get("runs", [])) < run_index:
        raise ValueError(f"no {mode} run {run_index}")
    segments = sorted(section["runs"][run_index - 1].get("segmentList", []), key=lambda s: s["startMs"])
    return " ".join(s.get("text", "") for s in segments)


def score(ref_text: str, hyp_text: str) -> tuple[WerCounts, list]:
    ops = align(normalize(ref_text), normalize(hyp_text))
    return count(ops), ops


def format_diff(ops: list) -> str:
    out = []
    for op, r, h in ops:
        if op == "ok":
            out.append(r)
        elif op == "sub":
            out.append(f"[{r}→{h}]")
        elif op == "del":
            out.append(f"[-{r}]")
        else:
            out.append(f"[+{h}]")
    return " ".join(out)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ref", required=True, type=Path)
    parser.add_argument("--pred", required=True, type=Path)
    parser.add_argument("--mode", default="streaming", choices=["batch", "streaming"])
    parser.add_argument("--run", default=1, type=int, help="1-based run index")
    parser.add_argument("--diff", action="store_true", help="print the aligned words")
    args = parser.parse_args()

    data = json.loads(args.pred.read_text(encoding="utf-8"))
    counts, ops = score(read_reference(args.ref), read_hypothesis(data, args.mode, args.run))
    print(f"file: {args.pred}  mode={args.mode} run={args.run}")
    print(
        f"WER {counts.wer * 100:.2f}%  (S={counts.substitutions} D={counts.deletions} "
        f"I={counts.insertions} / N={counts.ref_words})"
    )
    if args.diff:
        print(format_diff(ops))


if __name__ == "__main__":
    main()
