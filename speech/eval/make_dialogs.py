"""Conversations with real voices from the Dialogs corpus (langswap, OpenRAIL).

langswap/dialogs-ru-emotional-conversations: three professional actors
(Masha F, Sveta F, Dima M) performing scripted dialogs face to face; utterances
are cut out and numbered in order inside each dialog part. Consecutive
utterances of one part are glued back into a conversation with natural gaps
(0.15-0.9 s, sometimes a 0.2-0.4 s overlap), so the audio is real speech and
the reference (speaker, text, timing) is exact. Unlike the synthetic set this
has human voices and a hard same-gender pair (Masha/Sveta).

Conditions (4 conversations each), all with a -55 dBFS pink noise floor:
  clean  as recorded
  noise  pink noise at ~15 dB SNR
  far    the second speaker attenuated by 12 dB (the far-speaker case)

Reference turns are trimmed to the speech inside each utterance file
(frames above -45 dBFS), so timestamps are tighter than on synthetic data.

Usage: python make_dialogs.py <out_dir> [--cache <dir>] [--set2]

--set2 builds 12 other conversations (other parts and offsets) for checking
parameters tuned on the first set; never tune on it.
Needs numpy and ffmpeg in PATH; downloads ~200 MB of utterance WAVs once.
"""
from __future__ import annotations

import csv
import json
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

import numpy as np

from extract_synthetic import write_wav

REPO = "langswap/dialogs-ru-emotional-conversations"
BASE = f"https://huggingface.co/datasets/{REPO}/resolve/main/"
API = f"https://huggingface.co/api/datasets/{REPO}"
RATE = 16_000
UTTERANCES = 24

# (name, dialog part, first utterance, condition). Parts and offsets spread
# over the corpus; the second half is the same-gender pair.
CONVERSATIONS = [
    ("dlg_md01_clean", "masha_dima_part1", 40, "clean"),
    ("dlg_md03_noise", "masha_dima_part3", 120, "noise"),
    ("dlg_md05_far", "masha_dima_part5", 200, "far"),
    ("dlg_md07_clean", "masha_dima_part7", 60, "clean"),
    ("dlg_md09_noise", "masha_dima_part9", 150, "noise"),
    ("dlg_md11_far", "masha_dima_part11", 90, "far"),
    ("dlg_ms01_clean", "masha_sveta_part1", 50, "clean"),
    ("dlg_ms03_noise", "masha_sveta_part3", 130, "noise"),
    ("dlg_ms05_far", "masha_sveta_part5", 210, "far"),
    ("dlg_ms07_clean", "masha_sveta_part7", 70, "clean"),
    ("dlg_ms09_noise", "masha_sveta_part9", 160, "noise"),
    ("dlg_ms10_far", "masha_sveta_part10", 100, "far"),
]


# Validation set: other parts/offsets of the same corpus, same three conditions.
CONVERSATIONS2 = [
    ("dlg2_md02_clean", "masha_dima_part2", 250, "clean"),
    ("dlg2_md04_noise", "masha_dima_part4", 30, "noise"),
    ("dlg2_md06_far", "masha_dima_part6", 310, "far"),
    ("dlg2_md08_clean", "masha_dima_part8", 220, "clean"),
    ("dlg2_md10_noise", "masha_dima_part10", 280, "noise"),
    ("dlg2_md12_far", "masha_dima_part12", 20, "far"),
    ("dlg2_ms02_clean", "masha_sveta_part2", 260, "clean"),
    ("dlg2_ms04_noise", "masha_sveta_part4", 40, "noise"),
    ("dlg2_ms06_far", "masha_sveta_part6", 300, "far"),
    ("dlg2_ms08_clean", "masha_sveta_part8", 230, "clean"),
    ("dlg2_ms09_noise", "masha_sveta_part9", 330, "noise"),
    ("dlg2_ms10_far", "masha_sveta_part10", 250, "far"),
]


def fetch(url: str, path: Path, attempts: int = 3) -> Path:
    if path.exists():
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".part")
    for attempt in range(1, attempts + 1):
        try:
            # Без таймаута urlretrieve однажды повис на соединении на 5+ минут.
            with urllib.request.urlopen(url, timeout=60) as response, tmp.open("wb") as f:
                f.write(response.read())
            tmp.rename(path)
            return path
        except (OSError, TimeoutError) as e:
            tmp.unlink(missing_ok=True)
            if attempt == attempts:
                raise
            print(f"retry {attempt}/{attempts - 1} {url}: {e}", file=sys.stderr)
    return path


def load_16k(path: Path) -> np.ndarray:
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-ac", "1", "-ar", str(RATE), "-f", "f32le", "-"],
        check=True, capture_output=True,
    ).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


def speech_span(x: np.ndarray, frame: int = 320, level_db: float = -45.0) -> tuple[int, int]:
    n = len(x) // frame
    if n == 0:
        return 0, len(x)
    rms = np.sqrt(np.mean(x[: n * frame].reshape(n, frame) ** 2, axis=1)) + 1e-9
    loud = np.nonzero(20 * np.log10(rms) > level_db)[0]
    if len(loud) == 0:
        return 0, len(x)
    return int(loud[0] * frame), int(min((loud[-1] + 1) * frame, len(x)))


def pink(n: int, rng: np.random.Generator) -> np.ndarray:
    spectrum = np.fft.rfft(rng.standard_normal(n))
    spectrum /= np.sqrt(np.maximum(np.arange(len(spectrum)), 1))
    noise = np.fft.irfft(spectrum, n)
    return (noise / (np.sqrt(np.mean(noise ** 2)) + 1e-12)).astype(np.float32)


def rms(x: np.ndarray) -> float:
    return float(np.sqrt(np.mean(x ** 2)) + 1e-12)


def consecutive_window(utts: list, first: int, available: set[str]) -> list:
    """First run of UTTERANCES consecutive, actually uploaded utterances from `first` on.

    The repo holds ~10 000 of the 11 796 utterance files listed in metadata.csv.
    """
    by_index = {u[0]: u for u in utts}
    for start in range(first, max(by_index) - UTTERANCES + 2):
        run = [by_index.get(i) for i in range(start, start + UTTERANCES)]
        if all(u is not None and u[3] in available for u in run):
            return run
    raise SystemExit(f"no {UTTERANCES} consecutive uploaded utterances from {first}")


def build(name, part, first, condition, rows_by_part, available, cache, out, rng):
    utts = sorted(rows_by_part[part], key=lambda r: r[0])
    window = consecutive_window(utts, first, available)
    speakers = []
    for _, spk, _, _ in window:
        if spk not in speakers:
            speakers.append(spk)
    far_speaker = speakers[1] if condition == "far" and len(speakers) > 1 else None

    pieces, turns, cursor = [], [], 0
    for index, spk, text, rel in window:
        x = load_16k(fetch(BASE + rel, cache / rel))
        if spk == far_speaker:
            x = x * 10 ** (-12 / 20)
        a, b = speech_span(x)
        gap = rng.uniform(0.15, 0.9) if rng.random() > 0.1 else -rng.uniform(0.2, 0.4)
        start = max(0, cursor + int(gap * RATE) - a) if turns else 0
        pieces.append((start, x))
        turns.append({"speaker": spk, "start": (start + a) / RATE, "end": (start + b) / RATE, "text": text})
        cursor = start + b

    total = max(s + len(x) for s, x in pieces) + RATE // 2
    mix = np.zeros(total, dtype=np.float32)
    for s, x in pieces:
        mix[s:s + len(x)] += x
    speech_rms = rms(mix[mix != 0]) if np.any(mix) else 1.0
    mix += pink(total, rng) * 10 ** (-55 / 20)
    if condition == "noise":
        mix += pink(total, rng) * speech_rms * 10 ** (-15 / 20)
    peak = np.max(np.abs(mix))
    if peak > 0.99:
        mix *= 0.99 / peak

    write_wav(out / f"{name}.wav", mix, RATE)
    with (out / f"{name}.turns.jsonl").open("w", encoding="utf-8") as f:
        for t in turns:
            f.write(json.dumps(t, ensure_ascii=False) + "\n")
    with (out / f"{name}.rttm").open("w", encoding="utf-8") as f:
        for t in turns:
            f.write(f"SPEAKER {name} 1 {t['start']:.3f} {t['end'] - t['start']:.3f} <NA> <NA> {t['speaker']} <NA> <NA>\n")
    print(f"{name:16} {total / RATE:6.1f}s turns={len(turns)} speakers={''.join(speakers)} condition={condition}")


def main() -> None:
    args = sys.argv[1:]
    if not args:
        raise SystemExit(__doc__)
    out = Path(args[0])
    cache = Path(args[args.index("--cache") + 1]) if "--cache" in args else out / "cache"
    out.mkdir(parents=True, exist_ok=True)
    meta = fetch(BASE + "metadata.csv", cache / "metadata.csv")
    listing = fetch(API, cache / "listing.json")
    available = {s["rfilename"] for s in json.loads(listing.read_text(encoding="utf-8"))["siblings"]}
    rows_by_part: dict[str, list] = {}
    for r in csv.DictReader(meta.open(encoding="utf-8"), delimiter="|"):
        m = re.match(r"wavs/(.+)_(\d+)\.wav", r["audio_path"])
        rows_by_part.setdefault(m.group(1), []).append((int(m.group(2)), r["speaker_id"], r["text"], r["audio_path"]))
    second = "--set2" in args
    rng = np.random.default_rng(2027 if second else 2026)
    for name, part, first, condition in (CONVERSATIONS2 if second else CONVERSATIONS):
        build(name, part, first, condition, rows_by_part, available, cache, out, rng)


if __name__ == "__main__":
    main()
