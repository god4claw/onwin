#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: speech/scripts/benchmark.sh --wav <file> [options]

Options:
  --wav <file>        Canonical WAV: 16 kHz, mono, PCM16 (required)
  --mode <mode>       batch | streaming | both (default: batch)
  --speakers <value>  auto or a fixed number for batch (default: auto)
  --threads <n>       ASR threads (default: 4)
  --runs <n>          Runs per mode (default: 3)
  --chunk-ms <n>      Streaming chunk size in ms (default: 100)
  --serial <serial>   adb device serial (default: first device)
  --out <dir>         Local output directory (default: bench-results)
  --no-build          Skip Gradle build/install
  -h, --help          Show this help
EOF
}

WAV=""
MODE="batch"
SPEAKERS="auto"
THREADS=4
RUNS=3
CHUNK_MS=100
SERIAL=""
OUT_DIR="bench-results"
NO_BUILD=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --wav) WAV="${2:?--wav requires a value}"; shift 2 ;;
        --mode) MODE="${2:?--mode requires a value}"; shift 2 ;;
        --speakers) SPEAKERS="${2:?--speakers requires a value}"; shift 2 ;;
        --threads) THREADS="${2:?--threads requires a value}"; shift 2 ;;
        --runs) RUNS="${2:?--runs requires a value}"; shift 2 ;;
        --chunk-ms) CHUNK_MS="${2:?--chunk-ms requires a value}"; shift 2 ;;
        --serial) SERIAL="${2:?--serial requires a value}"; shift 2 ;;
        --out) OUT_DIR="${2:?--out requires a value}"; shift 2 ;;
        --no-build) NO_BUILD=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
    esac
done

if [[ -z "$WAV" ]]; then
    usage >&2
    exit 1
fi
if [[ ! -f "$WAV" ]]; then
    echo "WAV not found: $WAV" >&2
    exit 1
fi
if [[ ! "$MODE" =~ ^(batch|streaming|both)$ ]]; then
    echo "Invalid --mode: $MODE (expected batch, streaming or both)" >&2
    exit 1
fi
if [[ ! "$THREADS" =~ ^[0-9]+$ ]] || [[ "$THREADS" -lt 1 ]]; then
    echo "Invalid --threads: $THREADS" >&2
    exit 1
fi
if [[ ! "$RUNS" =~ ^[0-9]+$ ]] || [[ "$RUNS" -lt 1 ]]; then
    echo "Invalid --runs: $RUNS" >&2
    exit 1
fi
if [[ ! "$CHUNK_MS" =~ ^[0-9]+$ ]] || [[ "$CHUNK_MS" -lt 10 ]]; then
    echo "Invalid --chunk-ms: $CHUNK_MS" >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

ADB="${ADB:-}"

if [[ -z "$ADB" && -f "$ROOT_DIR/local.properties" ]]; then
    SDK_DIR="$(grep -E '^[[:space:]]*sdk\.dir[[:space:]]*=' "$ROOT_DIR/local.properties" |
        head -n 1 | cut -d= -f2- |
        sed -e 's/^[[:space:]]*//' -e 's/\\:/:/g' -e 's/\\\\/\\/g')"
    if [[ -n "$SDK_DIR" ]]; then
        if command -v cygpath >/dev/null 2>&1; then
            SDK_DIR="$(cygpath -u "$SDK_DIR")"
        fi
        if [[ -x "$SDK_DIR/platform-tools/adb" ]]; then
            ADB="$SDK_DIR/platform-tools/adb"
        elif [[ -x "$SDK_DIR/platform-tools/adb.exe" ]]; then
            ADB="$SDK_DIR/platform-tools/adb.exe"
        fi
    fi
fi
if [[ -z "$ADB" && -n "${LOCALAPPDATA:-}" ]]; then
    LOCAL_SDK="${LOCALAPPDATA//\\//}/Android/Sdk"
    if command -v cygpath >/dev/null 2>&1; then
        LOCAL_SDK="$(cygpath -u "$LOCAL_SDK")"
    fi
    if [[ -x "$LOCAL_SDK/platform-tools/adb.exe" ]]; then
        ADB="$LOCAL_SDK/platform-tools/adb.exe"
    fi
fi

if [[ -z "$ADB" ]]; then
    if command -v adb >/dev/null 2>&1; then
        ADB="$(command -v adb)"
    elif [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
        ADB="$ANDROID_HOME/platform-tools/adb"
    elif [[ -n "${ANDROID_SDK_ROOT:-}" && -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]]; then
        ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
    else
        echo "adb not found. Set ANDROID_HOME or add platform-tools to PATH." >&2
        exit 1
    fi
fi

ADB_ARGS=()
if [[ -n "$SERIAL" ]]; then
    ADB_ARGS+=(-s "$SERIAL")
fi

if [[ "$NO_BUILD" -eq 0 ]]; then
    echo "Building :speech androidTest APK..."
    (
        cd "$ROOT_DIR"
        ./gradlew --no-daemon :speech:verifyBundledModels :speech:installDebugAndroidTest
    )
fi

PACKAGE="com.example.speech.test"
# Internal storage, а не /sdcard/Android/data/<pkg>: на Android 11+ последний путь —
# FUSE-представление scoped storage, и файл, залитый adb push от shell, приложение
# через File() не видит ("WAV не найден", хотя файл на месте). Через run-as копия
# сразу получает uid приложения.
REMOTE_STAGING="/data/local/tmp/bench-input.wav"
REMOTE_DIR="/data/user/0/$PACKAGE/files/bench"
REMOTE_RESULTS="$REMOTE_DIR/results"
REMOTE_WAV="$REMOTE_DIR/input.wav"
REMOTE_JSON="$REMOTE_RESULTS/result.json"
REMOTE_TXT="$REMOTE_RESULTS/result.txt"

to_host_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$1"
    else
        printf '%s\n' "$1"
    fi
}

echo "Preparing device directory..."
"$ADB" "${ADB_ARGS[@]}" shell run-as "$PACKAGE" mkdir -p "$REMOTE_DIR"
"$ADB" "${ADB_ARGS[@]}" shell run-as "$PACKAGE" mkdir -p "$REMOTE_RESULTS"

echo "Pushing $WAV ..."
"$ADB" "${ADB_ARGS[@]}" push "$(to_host_path "$WAV")" "$REMOTE_STAGING"
if ! "$ADB" "${ADB_ARGS[@]}" shell run-as "$PACKAGE" cp "$REMOTE_STAGING" "$REMOTE_WAV"; then
    echo "Failed to copy WAV into internal storage via run-as." >&2
    echo "run-as requires a debuggable build: reinstall without --no-build." >&2
    exit 1
fi
"$ADB" "${ADB_ARGS[@]}" shell rm -f "$REMOTE_STAGING"

LABEL="$(basename "$WAV")"
LABEL="${LABEL%.*}"

echo "Running benchmark (mode=$MODE, runs=$RUNS, threads=$THREADS)..."
set +e
INSTRUMENT_OUTPUT="$(
    "$ADB" "${ADB_ARGS[@]}" shell am instrument -w -r \
        -e class com.example.speech.bench.SpeechBenchmarkTest \
        -e wav "$REMOTE_WAV" \
        -e label "$LABEL" \
        -e mode "$MODE" \
        -e speakers "$SPEAKERS" \
        -e threads "$THREADS" \
        -e runs "$RUNS" \
        -e chunk_ms "$CHUNK_MS" \
        -e out "$REMOTE_JSON" \
        com.example.speech.test/androidx.test.runner.AndroidJUnitRunner 2>&1
)"
INSTRUMENT_STATUS=$?
set -e

printf '%s\n' "$INSTRUMENT_OUTPUT" |
    grep -E 'INSTRUMENTATION_|OK \(|FAILURES|Tests run' || true

if [[ "$INSTRUMENT_STATUS" -ne 0 ]] ||
    grep -q 'FAILURES!!!' <<<"$INSTRUMENT_OUTPUT" ||
    grep -qE 'Tests run:.*Failures: [1-9]' <<<"$INSTRUMENT_OUTPUT" ||
    grep -q 'INSTRUMENTATION_STATUS_CODE: -2' <<<"$INSTRUMENT_OUTPUT"; then
    echo "Instrumentation failed." >&2
    printf '%s\n' "$INSTRUMENT_OUTPUT" >&2
    exit 1
fi

if [[ "$OUT_DIR" != /* && ! "$OUT_DIR" =~ ^[A-Za-z]:[/\\] ]]; then
    OUT_DIR="$ROOT_DIR/$OUT_DIR"
fi
mkdir -p "$OUT_DIR"

STAMP="$(date +%Y%m%d-%H%M%S)"
BASE="$(basename "$WAV")"
BASE="${BASE%.*}"
LOCAL_JSON="$OUT_DIR/$BASE-$STAMP.json"
LOCAL_TXT="$OUT_DIR/$BASE-$STAMP.txt"

# adb pull не читает internal storage чужого приложения: забираем через run-as.
echo "Pulling results..."
pull_via_run_as() {
    local remote="$1" local_path="$2"
    if ! "$ADB" "${ADB_ARGS[@]}" exec-out run-as "$PACKAGE" cat "$remote" >"$local_path"; then
        echo "Failed to read $remote from device." >&2
        exit 1
    fi
    echo "pulled $remote -> $local_path"
}
pull_via_run_as "$REMOTE_JSON" "$LOCAL_JSON"
pull_via_run_as "$REMOTE_TXT" "$LOCAL_TXT"

"$ADB" "${ADB_ARGS[@]}" shell run-as "$PACKAGE" rm -f "$REMOTE_WAV" >/dev/null

echo
cat "$LOCAL_TXT"
echo
echo "JSON: $LOCAL_JSON"
echo "TXT:  $LOCAL_TXT"
