#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: speech/scripts/benchmark.sh --wav <file> [options]

Options:
  --wav <file>        Canonical WAV: 16 kHz, mono, PCM16 (required)
  --mode <mode>       batch | streaming | both | vad (default: batch)
                      vad = file through SherpaVadTranscriber (what the app gets)
  --speakers <value>  auto or a fixed number for batch (default: auto)
  --threads <n>       ASR threads (default: 4)
  --runs <n>          Runs per mode (default: 3)
  --chunk-ms <n>      Streaming chunk size in ms (default: 100)
  --auto-threshold <x>    Batch auto-cluster threshold, 0..1 (default: 0.6)
  --online-threshold <x>  Streaming online-cluster threshold, 0..1 (default: 0.6)
  --prune-min-ms <n>      Minimum cluster duration after pruning (default: 3000)
  --prune-min-fraction <x> Minimum cluster speech fraction, 0..1 (default: 0.05)
  --speaker-change-threshold <x> Speaker-change cosine threshold, 0..1 (default: 0.65)
  --pace <x>          Streaming feed speed: 0 = as fast as possible (default),
                      1 = real time like a live microphone (measures latency)
  --vad-threshold <x>        Silero VAD threshold, 0..1 (default: VadConfig)
  --vad-min-silence-ms <n>   Silence that closes an utterance (default: VadConfig, 450)
  --vad-min-speech-ms <n>    Minimum speech length (default: VadConfig, 250)
  --vad-max-speech-ms <n>    Forced utterance cut (default: VadConfig, 25000)
  --agc <on|off>             Streaming gain control before VAD (default: AgcConfig, on)
  --agc-target-db <x>        AGC target speech level, dBFS (default: AgcConfig, -20)
  --agc-max-gain-db <x>      AGC gain ceiling, dB (default: AgcConfig, 24)
  --dump-embeddings          Write each streaming segment's speaker embedding to the JSON
  --recluster-threshold <x>  Streaming final speaker re-clustering threshold, 0..1 (default: SpeechConfig, 0.60)
  --speaker-change-window-ms <n>  CAM++ window for in-chunk speaker change (default: SpeechConfig, 1600)
  --speaker-change-hop-ms <n>     Step between those windows (default: SpeechConfig, 800)
  --speaker-change-min-ms <n>     Minimum distance between change points (default: SpeechConfig, 1500)
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
AUTO_THRESHOLD="0.80"
ONLINE_THRESHOLD="0.45"
PRUNE_MIN_MS=3000
PRUNE_MIN_FRACTION="0.05"
SPEAKER_CHANGE_THRESHOLD="0.65"
PACE="0"
# VAD: пустое значение — не передавать, тест возьмёт значения VadConfig по умолчанию.
VAD_THRESHOLD=""
VAD_MIN_SILENCE_MS=""
VAD_MIN_SPEECH_MS=""
VAD_MAX_SPEECH_MS=""
AGC=""
AGC_TARGET_DB=""
AGC_MAX_GAIN_DB=""
DUMP_EMBEDDINGS=0
RECLUSTER_THRESHOLD=""
CHANGE_WINDOW_MS=""
CHANGE_HOP_MS=""
CHANGE_MIN_MS=""
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
        --auto-threshold) AUTO_THRESHOLD="${2:?--auto-threshold requires a value}"; shift 2 ;;
        --online-threshold) ONLINE_THRESHOLD="${2:?--online-threshold requires a value}"; shift 2 ;;
        --prune-min-ms) PRUNE_MIN_MS="${2:?--prune-min-ms requires a value}"; shift 2 ;;
        --prune-min-fraction) PRUNE_MIN_FRACTION="${2:?--prune-min-fraction requires a value}"; shift 2 ;;
        --speaker-change-threshold) SPEAKER_CHANGE_THRESHOLD="${2:?--speaker-change-threshold requires a value}"; shift 2 ;;
        --pace) PACE="${2:?--pace requires a value}"; shift 2 ;;
        --vad-threshold) VAD_THRESHOLD="${2:?--vad-threshold requires a value}"; shift 2 ;;
        --vad-min-silence-ms) VAD_MIN_SILENCE_MS="${2:?--vad-min-silence-ms requires a value}"; shift 2 ;;
        --vad-min-speech-ms) VAD_MIN_SPEECH_MS="${2:?--vad-min-speech-ms requires a value}"; shift 2 ;;
        --vad-max-speech-ms) VAD_MAX_SPEECH_MS="${2:?--vad-max-speech-ms requires a value}"; shift 2 ;;
        --agc) AGC="${2:?--agc requires a value}"; shift 2 ;;
        --agc-target-db) AGC_TARGET_DB="${2:?--agc-target-db requires a value}"; shift 2 ;;
        --agc-max-gain-db) AGC_MAX_GAIN_DB="${2:?--agc-max-gain-db requires a value}"; shift 2 ;;
        --dump-embeddings) DUMP_EMBEDDINGS=1; shift ;;
        --recluster-threshold) RECLUSTER_THRESHOLD="${2:?--recluster-threshold requires a value}"; shift 2 ;;
        --speaker-change-window-ms) CHANGE_WINDOW_MS="${2:?--speaker-change-window-ms requires a value}"; shift 2 ;;
        --speaker-change-hop-ms) CHANGE_HOP_MS="${2:?--speaker-change-hop-ms requires a value}"; shift 2 ;;
        --speaker-change-min-ms) CHANGE_MIN_MS="${2:?--speaker-change-min-ms requires a value}"; shift 2 ;;
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
if [[ ! "$MODE" =~ ^(batch|streaming|both|vad)$ ]]; then
    echo "Invalid --mode: $MODE (expected batch, streaming, both or vad)" >&2
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
if ! awk -v v="$AUTO_THRESHOLD" 'BEGIN { exit !(v + 0 == v && v >= 0 && v <= 1) }'; then
    echo "Invalid --auto-threshold: $AUTO_THRESHOLD (expected 0..1)" >&2
    exit 1
fi
if ! awk -v v="$ONLINE_THRESHOLD" 'BEGIN { exit !(v + 0 == v && v >= 0 && v <= 1) }'; then
    echo "Invalid --online-threshold: $ONLINE_THRESHOLD (expected 0..1)" >&2
    exit 1
fi
if [[ ! "$PRUNE_MIN_MS" =~ ^[0-9]+$ ]]; then
    echo "Invalid --prune-min-ms: $PRUNE_MIN_MS (expected >= 0)" >&2
    exit 1
fi
if ! awk -v v="$PRUNE_MIN_FRACTION" 'BEGIN { exit !(v + 0 == v && v >= 0 && v <= 1) }'; then
    echo "Invalid --prune-min-fraction: $PRUNE_MIN_FRACTION (expected 0..1)" >&2
    exit 1
fi
if ! awk -v v="$SPEAKER_CHANGE_THRESHOLD" 'BEGIN { exit !(v + 0 == v && v >= 0 && v <= 1) }'; then
    echo "Invalid --speaker-change-threshold: $SPEAKER_CHANGE_THRESHOLD (expected 0..1)" >&2
    exit 1
fi

if ! awk -v v="$PACE" 'BEGIN { exit !(v + 0 == v && v >= 0) }'; then
    echo "Invalid --pace: $PACE (expected >= 0)" >&2
    exit 1
fi
if [[ -n "$VAD_THRESHOLD" ]] &&
    ! awk -v v="$VAD_THRESHOLD" 'BEGIN { exit !(v + 0 == v && v >= 0 && v <= 1) }'; then
    echo "Invalid --vad-threshold: $VAD_THRESHOLD (expected 0..1)" >&2
    exit 1
fi
for pair in "vad-min-silence-ms:$VAD_MIN_SILENCE_MS" "vad-min-speech-ms:$VAD_MIN_SPEECH_MS" \
    "vad-max-speech-ms:$VAD_MAX_SPEECH_MS"; do
    value="${pair#*:}"
    if [[ -n "$value" && ! "$value" =~ ^[0-9]+$ ]]; then
        echo "Invalid --${pair%%:*}: $value (expected integer ms)" >&2
        exit 1
    fi
done

if [[ -n "$AGC" && ! "$AGC" =~ ^(on|off)$ ]]; then
    echo "Invalid --agc: $AGC (expected on or off)" >&2
    exit 1
fi
for pair in "agc-target-db:$AGC_TARGET_DB" "agc-max-gain-db:$AGC_MAX_GAIN_DB"; do
    value="${pair#*:}"
    if [[ -n "$value" ]] && ! awk -v v="$value" 'BEGIN { exit !(v + 0 == v) }'; then
        echo "Invalid --${pair%%:*}: $value (expected a number)" >&2
        exit 1
    fi
done

OPTIONAL_ARGS=()
[[ -n "$AGC" ]] && OPTIONAL_ARGS+=(-e agc "$AGC")
[[ -n "$AGC_TARGET_DB" ]] && OPTIONAL_ARGS+=(-e agc_target_db "$AGC_TARGET_DB")
[[ -n "$AGC_MAX_GAIN_DB" ]] && OPTIONAL_ARGS+=(-e agc_max_gain_db "$AGC_MAX_GAIN_DB")
[[ "$DUMP_EMBEDDINGS" -eq 1 ]] && OPTIONAL_ARGS+=(-e dump_embeddings on)
for pair in "speaker_change_window_ms:$CHANGE_WINDOW_MS" "speaker_change_hop_ms:$CHANGE_HOP_MS" \
    "speaker_change_min_ms:$CHANGE_MIN_MS"; do
    value="${pair#*:}"
    [[ -z "$value" ]] && continue
    if [[ ! "$value" =~ ^[0-9]+$ ]]; then
        echo "Invalid ${pair%%:*}: $value (expected integer ms)" >&2
        exit 1
    fi
    OPTIONAL_ARGS+=(-e "${pair%%:*}" "$value")
done
if [[ -n "$RECLUSTER_THRESHOLD" ]]; then
    if ! awk -v v="$RECLUSTER_THRESHOLD" 'BEGIN { exit !(v + 0 == v && v >= 0 && v <= 1) }'; then
        echo "Invalid --recluster-threshold: $RECLUSTER_THRESHOLD (expected 0..1)" >&2
        exit 1
    fi
    OPTIONAL_ARGS+=(-e recluster_threshold "$RECLUSTER_THRESHOLD")
fi
[[ -n "$VAD_THRESHOLD" ]] && OPTIONAL_ARGS+=(-e vad_threshold "$VAD_THRESHOLD")
[[ -n "$VAD_MIN_SILENCE_MS" ]] && OPTIONAL_ARGS+=(-e vad_min_silence_ms "$VAD_MIN_SILENCE_MS")
[[ -n "$VAD_MIN_SPEECH_MS" ]] && OPTIONAL_ARGS+=(-e vad_min_speech_ms "$VAD_MIN_SPEECH_MS")
[[ -n "$VAD_MAX_SPEECH_MS" ]] && OPTIONAL_ARGS+=(-e vad_max_speech_ms "$VAD_MAX_SPEECH_MS")

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
        # bash, а не ./gradlew: в git у gradlew нет +x, и на Linux/macOS прямой запуск падает.
        bash ./gradlew --no-daemon :speech:verifyBundledModels :speech:installDebugAndroidTest
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
# Иначе при редком сбое instrumentation можно вытащить result.json предыдущего прогона.
"$ADB" "${ADB_ARGS[@]}" shell run-as "$PACKAGE" rm -f "$REMOTE_JSON"
"$ADB" "${ADB_ARGS[@]}" shell run-as "$PACKAGE" rm -f "$REMOTE_TXT"

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

echo "Running benchmark (mode=$MODE, runs=$RUNS, threads=$THREADS, pace=$PACE)..."
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
        -e auto_threshold "$AUTO_THRESHOLD" \
        -e online_threshold "$ONLINE_THRESHOLD" \
        -e prune_min_ms "$PRUNE_MIN_MS" \
        -e prune_min_fraction "$PRUNE_MIN_FRACTION" \
        -e speaker_change_threshold "$SPEAKER_CHANGE_THRESHOLD" \
        -e pace "$PACE" \
        ${OPTIONAL_ARGS[@]+"${OPTIONAL_ARGS[@]}"} \
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
    grep -q 'INSTRUMENTATION_STATUS_CODE: -2' <<<"$INSTRUMENT_OUTPUT" ||
    # Нативный SIGSEGV убивает процесс: JUnit не успевает написать FAILURES,
    # а am instrument всё равно завершается с кодом 0.
    grep -q 'INSTRUMENTATION_RESULT: shortMsg=' <<<"$INSTRUMENT_OUTPUT"; then
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
    # adb exec-out не передаёт код возврата удалённой команды: без этой проверки
    # текст «No such file» молча попадал бы в локальный .json.
    if ! "$ADB" "${ADB_ARGS[@]}" shell run-as "$PACKAGE" test -f "$remote"; then
        echo "Result $remote is missing on device: the benchmark did not finish." >&2
        exit 1
    fi
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
