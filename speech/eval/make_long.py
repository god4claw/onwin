"""Длинная запись для проверки пайплайна на часах аудио.

Склеивает WAV наборов (по умолчанию живые диалоги: три актёра M/D/S во всех
файлах) подряд с паузой и повторяет весь круг N раз. Пишет рядом:

  <label>.wav           16 кГц, моно, PCM16 — пишется потоково, без загрузки целиком;
  <label>.turns.jsonl   эталон реплик со сдвигом времени (WER);
  <label>.rttm          эталон спикеров (DER, атрибуция);
  <label>.blocks.json   границы исходных файлов — score_long.py считает WER по ним.

  python speech/eval/make_long.py --sets speech/eval/dialogs speech/eval/dialogs2 \
      --repeat 6 --label long_8h --out speech/eval/long
"""
import argparse
import json
import wave
from pathlib import Path

SR = 16_000


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sets", nargs="+", required=True, type=Path)
    ap.add_argument("--repeat", type=int, default=1)
    ap.add_argument("--gap", type=float, default=1.0, help="пауза между файлами, с")
    ap.add_argument("--label", required=True)
    ap.add_argument("--out", required=True, type=Path)
    a = ap.parse_args()

    files = [p for d in a.sets for p in sorted(d.glob("*.wav")) if Path(str(p)[:-4] + ".turns.jsonl").exists()]
    if not files:
        raise SystemExit("нет WAV с .turns.jsonl")
    a.out.mkdir(parents=True, exist_ok=True)
    gap = b"\x00\x00" * int(a.gap * SR)

    blocks, turns, rttm = [], [], []
    offset = 0  # сэмплы
    with wave.open(str(a.out / f"{a.label}.wav"), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(SR)
        for round_index in range(a.repeat):
            for p in files:
                with wave.open(str(p)) as w:
                    assert (w.getframerate(), w.getnchannels(), w.getsampwidth()) == (SR, 1, 2), p
                    frames = w.getnframes()
                    out.writeframes(w.readframes(frames))
                start_s = offset / SR
                blocks.append({"file": f"{p.parent.name}/{p.stem}", "round": round_index,
                               "start": start_s, "end": start_s + frames / SR})
                for line in Path(str(p)[:-4] + ".turns.jsonl").read_text(encoding="utf-8").splitlines():
                    if line.strip():
                        t = json.loads(line)
                        t["start"] = float(t["start"]) + start_s
                        t["end"] = float(t["end"]) + start_s
                        turns.append(t)
                for line in Path(str(p)[:-4] + ".rttm").read_text(encoding="utf-8").splitlines():
                    parts = line.split()
                    if len(parts) >= 8 and parts[0] == "SPEAKER":
                        rttm.append((float(parts[3]) + start_s, float(parts[4]), parts[7]))
                out.writeframes(gap)
                offset += frames + len(gap) // 2

    (a.out / f"{a.label}.turns.jsonl").write_text(
        "".join(json.dumps(t, ensure_ascii=False) + "\n" for t in turns), encoding="utf-8")
    (a.out / f"{a.label}.rttm").write_text(
        "".join(f"SPEAKER {a.label} 1 {s:.3f} {d:.3f} <NA> <NA> {spk} <NA> <NA>\n" for s, d, spk in rttm),
        encoding="utf-8")
    (a.out / f"{a.label}.blocks.json").write_text(json.dumps(blocks, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{a.label}: {len(blocks)} блоков, {offset / SR / 3600:.2f} ч, "
          f"спикеры {sorted({spk for _, _, spk in rttm})}")


if __name__ == "__main__":
    main()
