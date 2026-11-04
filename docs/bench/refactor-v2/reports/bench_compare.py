#!/usr/bin/env python3
"""Сравнение прогонов бенчмарка :speech между двумя сборками (before/after).

Читает JSON из каталогов, собранные speech/scripts/benchmark.ps1, группирует
по (фикстура, режим), считает медиану/разброс по прогонам и дельту AFTER к
BEFORE. Отдельно проверяет, что списки сегментов совпадают: рефакторинг не
должен менять ни текст, ни границы, ни номера говорящих.
"""

import argparse
import glob
import hashlib
import json
import os
import random
import statistics
from collections import defaultdict

BATCH_METRICS = [
    ("totalMs", "total, мс"),
    ("diarizationMs", "диаризация, мс"),
    ("recognitionMs", "ASR, мс"),
    ("rtf", "RTF"),
    ("pssMb", "PSS, МБ"),
    ("speakerCount", "спикеров"),
    ("segments", "сегментов"),
]

STREAM_METRICS = [
    ("totalMs", "total, мс"),
    ("drainMs", "drain, мс"),
    ("computeRtf", "compute RTF"),
    ("overrunMs", "отставание, мс"),
    ("firstPartialMs", "1-й Partial, мс"),
    ("partialLagMedianMs", "лаг Partial (мед.), мс"),
    ("finalizeLagMaxMs", "финализация (макс.), мс"),
    ("firstUtteranceMs", "1-я реплика, мс"),
    ("partials", "Partial, шт"),
    ("pssMb", "PSS, МБ"),
    ("speakerCount", "спикеров"),
    ("segments", "сегментов"),
]


def load_runs(directory):
    groups = defaultdict(list)
    if not os.path.isdir(directory):
        return groups
    for path in sorted(glob.glob(os.path.join(directory, "*.json"))):
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
        label = data.get("label") or os.path.basename(path).split("-")[0]
        mode = data.get("mode", "batch")
        pace = (data.get("config") or {}).get("pace", "realtime")
        device = data.get("device") or {}
        if mode in ("batch", "both") and data.get("batch"):
            for run in data["batch"].get("runs", []):
                groups[(label, "batch", "")].append(
                    {"run": run, "file": path, "device": device,
                     "loadMs": data["batch"].get("modelLoadMs")}
                )
        if mode in ("streaming", "both") and data.get("streaming"):
            for run in data["streaming"].get("runs", []):
                groups[(label, "streaming", pace)].append(
                    {"run": run, "file": path, "device": device,
                     "loadMs": data["streaming"].get("modelLoadMs")}
                )
    return groups


def values(entries, metric):
    result = []
    for entry in entries:
        value = entry["run"].get(metric)
        if value is not None:
            result.append(float(value))
    return result


def describe(items):
    if not items:
        return None
    return {
        "n": len(items),
        "median": statistics.median(items),
        "mean": statistics.fmean(items),
        "stdev": statistics.stdev(items) if len(items) > 1 else 0.0,
        "min": min(items),
        "max": max(items),
    }


def bootstrap_median_delta(before, after, iterations=4000, seed=7):
    if len(before) < 2 or len(after) < 2:
        return None
    rng = random.Random(seed)
    deltas = []
    for _ in range(iterations):
        sample_b = [rng.choice(before) for _ in before]
        sample_a = [rng.choice(after) for _ in after]
        deltas.append(statistics.median(sample_a) - statistics.median(sample_b))
    deltas.sort()
    return deltas[int(0.025 * iterations)], deltas[int(0.975 * iterations) - 1]


def segment_signature(run):
    segments = run.get("segmentList") or []
    payload = json.dumps(
        [(s.get("speaker"), s.get("startMs"), s.get("endMs"), s.get("text")) for s in segments],
        ensure_ascii=False,
        sort_keys=True,
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:12]


def format_value(value, fraction=0):
    if value is None:
        return "—"
    if fraction:
        return f"{value:.3f}"
    return f"{value:.0f}"


def compare(before, after, metrics, title, mode):
    lines = [f"### {title}", ""]
    lines.append("| фикстура | метрика | BEFORE мед. [min–max] | AFTER мед. [min–max] | Δ | 95% CI Δ (бутстрэп) |")
    lines.append("|---|---|---:|---:|---:|---:|")
    for key in sorted(set(before) | set(after)):
        if key[1] != mode:
            continue
        b_entries = before.get(key, [])
        a_entries = after.get(key, [])
        if not b_entries or not a_entries:
            continue
        label = key[0]
        extra = "" if not key[2] else f" ({key[2]})"
        for metric, human in metrics:
            b_vals = values(b_entries, metric)
            a_vals = values(a_entries, metric)
            b_stat = describe(b_vals)
            a_stat = describe(a_vals)
            if not b_stat or not a_stat:
                continue
            delta = (a_stat["median"] - b_stat["median"]) / b_stat["median"] * 100 if b_stat["median"] else 0.0
            ci = bootstrap_median_delta(b_vals, a_vals)
            ci_text = "—"
            if ci:
                low = ci[0] / b_stat["median"] * 100 if b_stat["median"] else 0.0
                high = ci[1] / b_stat["median"] * 100 if b_stat["median"] else 0.0
                ci_text = f"[{low:+.1f}%; {high:+.1f}%]"
            fraction = 1 if metric in ("rtf", "computeRtf") else 0
            lines.append(
                f"| {label}{extra} | {human} | "
                f"{format_value(b_stat['median'], fraction)} "
                f"[{format_value(b_stat['min'], fraction)}–{format_value(b_stat['max'], fraction)}] | "
                f"{format_value(a_stat['median'], fraction)} "
                f"[{format_value(a_stat['min'], fraction)}–{format_value(a_stat['max'], fraction)}] | "
                f"{delta:+.1f}% | {ci_text} |"
            )
    lines.append("")
    return lines


def quality(before, after, title):
    lines = [f"### {title}: сегменты", ""]
    lines.append("| группа | прогонов BEFORE/AFTER | одинаковый вывод |")
    lines.append("|---|---:|---:|")
    for key in sorted(set(before) | set(after)):
        b_sigs = {segment_signature(entry["run"]) for entry in before.get(key, [])}
        a_sigs = {segment_signature(entry["run"]) for entry in after.get(key, [])}
        if not b_sigs or not a_sigs:
            continue
        same = "да" if b_sigs == a_sigs else f"нет (B={len(b_sigs)}, A={len(a_sigs)})"
        lines.append(f"| {key[0]} {key[1]} {key[2]} | {len(before[key])}/{len(after[key])} | {same} |")
    lines.append("")
    return lines


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--before", required=True)
    parser.add_argument("--after", required=True)
    parser.add_argument("--repeat", default=None, help="второй прогон AFTER для оценки дрейфа")
    parser.add_argument("--out", default="bench-compare.md")
    args = parser.parse_args()

    before = load_runs(args.before)
    after = load_runs(args.after)

    lines = ["# Бенчмарк: BEFORE vs AFTER", ""]
    devices = {}
    for group in (before, after):
        for entries in group.values():
            for entry in entries:
                d = entry["device"]
                if d:
                    devices[d.get("model", "?")] = d
    for model, device in devices.items():
        lines.append(
            f"Устройство: {model}, Android {device.get('android')}, "
            f"{device.get('cores')} ядер, RAM {device.get('ramGb')} ГБ, ABI {device.get('abi')}."
        )
    lines.append("")

    lines += compare(before, after, BATCH_METRICS, "Batch", "batch")
    lines += compare(before, after, STREAM_METRICS, "Streaming", "streaming")
    lines += quality(before, after, "Batch/streaming")

    if args.repeat:
        repeat = load_runs(args.repeat)
        lines += ["## Дрейф: AFTER (прогретый повтор) vs AFTER (первый)", ""]
        lines += compare(after, repeat, BATCH_METRICS, "Batch, повтор", "batch")
        lines += compare(after, repeat, STREAM_METRICS, "Streaming, повтор", "streaming")

    text = "\n".join(lines) + "\n"
    with open(args.out, "w", encoding="utf-8") as handle:
        handle.write(text)
    print(text)


if __name__ == "__main__":
    main()
