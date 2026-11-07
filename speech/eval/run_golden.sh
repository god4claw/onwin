#!/usr/bin/env bash
# Прогоняет speech/scripts/benchmark.sh по всему золотому набору и печатает
# сводную таблицу stream_report.py.
#
# Usage: bash speech/eval/run_golden.sh <out_dir> [--with-long] [benchmark.sh options...]
#
#   <out_dir>     куда сложить JSON/TXT (относительно корня репозитория)
#   --with-long   добавить golden_long.wav (~6 мин; при --pace 1 идёт 6+ мин)
#   остальное     передаётся в benchmark.sh как есть, например:
#                 --mode streaming --pace 1 --runs 1 --vad-min-silence-ms 300
#
# Набор собирается make_golden.py в speech/eval/golden (см. speech/eval/README.md).
# APK собирается и ставится один раз, на первом файле (если не передан --no-build).
set -euo pipefail

if [[ $# -lt 1 || "$1" == -* ]]; then
    sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GOLDEN_DIR="${GOLDEN_DIR:-$ROOT_DIR/speech/eval/golden}"
OUT_DIR="$1"
shift

WITH_LONG=0
NO_BUILD=0
FORWARD=()
for arg in "$@"; do
    case "$arg" in
        --with-long) WITH_LONG=1 ;;
        --no-build) NO_BUILD=1 ;;
        *) FORWARD+=("$arg") ;;
    esac
done

shopt -s nullglob
WAVS=()
for wav in "$GOLDEN_DIR"/*.wav; do
    if [[ "$(basename "$wav")" == golden_long.wav && "$WITH_LONG" -eq 0 ]]; then
        continue
    fi
    WAVS+=("$wav")
done
if [[ ${#WAVS[@]} -eq 0 ]]; then
    echo "No WAV files in $GOLDEN_DIR. Build the set with speech/eval/make_golden.py." >&2
    exit 1
fi

cd "$ROOT_DIR"
FAILED=()
for wav in "${WAVS[@]}"; do
    echo "=== $(basename "$wav")"
    BUILD_FLAG=()
    if [[ "$NO_BUILD" -eq 1 ]]; then
        BUILD_FLAG=(--no-build)
    fi
    # Один упавший файл (например, нативный crash) не должен обрывать серию.
    if ! bash speech/scripts/benchmark.sh --wav "$wav" --out "$OUT_DIR" \
        ${BUILD_FLAG[@]+"${BUILD_FLAG[@]}"} ${FORWARD[@]+"${FORWARD[@]}"} >/dev/null; then
        FAILED+=("$(basename "$wav")")
    fi
    NO_BUILD=1
done

echo
python3 speech/eval/stream_report.py "$OUT_DIR" --refs "$GOLDEN_DIR"
if [[ ${#FAILED[@]} -gt 0 ]]; then
    echo
    echo "FAILED: ${FAILED[*]} (see the instrumentation output above)" >&2
    exit 1
fi
