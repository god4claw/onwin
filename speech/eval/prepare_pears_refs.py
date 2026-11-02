"""Parse Pear Chats per-speaker Praat TextGrids into words, turns, and RTTM.

TextGrids are UTF-16 with a BOM and contain tiers EDU, Words, Collat.
Only the Words tier carries plain orthographic words with timestamps.

Usage:
  python prepare_pears_refs.py <textgrid_dir> <out_dir>

Expected filenames like pears22N-voc.TextGrid, pears22C-voc.TextGrid,
pears22R-voc.TextGrid. Speaker code is the letter before '-voc'.
"""
from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path


@dataclass
class Word:
    start: float
    end: float
    text: str


@dataclass
class Turn:
    speaker: str
    start: float
    end: float
    text: str


INTERVAL_RE = re.compile(
    r"xmin\s*=\s*([0-9.]+)\s*xmax\s*=\s*([0-9.]+)\s*text\s*=\s*\"(.*?)\"",
    re.S,
)


def read_words(path: Path) -> list[Word]:
    text = path.read_bytes().decode("utf-16")
    marker = 'name = "Words"'
    at = text.find(marker)
    if at < 0:
        raise SystemExit(f"no Words tier in {path}")
    rest = text[at + len(marker) :]
    next_tier = rest.find('name = "')
    block = rest if next_tier < 0 else rest[:next_tier]
    words: list[Word] = []
    for m in INTERVAL_RE.finditer(block):
        start, end, label = float(m.group(1)), float(m.group(2)), m.group(3).strip()
        if label and label not in {"(?)", "(??)", "(?)"}:
            words.append(Word(start, end, label))
    return words


def merge_turns(speaker: str, words: list[Word], max_gap: float = 0.6) -> list[Turn]:
    turns: list[Turn] = []
    for word in words:
        if turns and word.start - turns[-1].end <= max_gap:
            prev = turns[-1]
            prev.end = max(prev.end, word.end)
            prev.text = (prev.text + " " + word.text).strip()
        else:
            turns.append(Turn(speaker, word.start, word.end, word.text))
    return turns


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    src = Path(sys.argv[1])
    out = Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)

    all_turns: list[Turn] = []
    all_words: list[dict] = []
    for path in sorted(src.glob("*-voc.TextGrid")):
        speaker = path.name.split("-voc")[0][-1]
        words = read_words(path)
        turns = merge_turns(speaker, words)
        all_words.extend(
            {
                "file": path.stem,
                "speaker": speaker,
                "start": round(w.start, 3),
                "end": round(w.end, 3),
                "text": w.text,
            }
            for w in words
        )
        all_turns.extend(turns)
        print(f"{path.name}: speaker={speaker}, words={len(words)}, turns={len(turns)}")

    all_turns.sort(key=lambda t: (t.start, t.speaker))
    with (out / "words.jsonl").open("w", encoding="utf-8") as f:
        for row in all_words:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    with (out / "turns.jsonl").open("w", encoding="utf-8") as f:
        for t in all_turns:
            f.write(
                json.dumps(
                    {
                        "speaker": t.speaker,
                        "start": round(t.start, 3),
                        "end": round(t.end, 3),
                        "text": t.text,
                    },
                    ensure_ascii=False,
                )
                + "\n"
            )
    with (out / "ref.rttm").open("w", encoding="utf-8") as f:
        for t in all_turns:
            f.write(
                f"SPEAKER pears22 1 {t.start:.3f} {t.end - t.start:.3f} "
                f"<NA> <NA> {t.speaker} <NA> <NA>\n"
            )
    print(f"wrote {len(all_words)} words, {len(all_turns)} turns to {out}")


if __name__ == "__main__":
    main()
