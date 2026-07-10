#!/usr/bin/env bash
#
# run_full.sh -- Orchestration script for the official RegHUSP-Parallel experiments.
#
# Compiles all Java sources, runs the six-scenario suite (test.RunFull) and/or the
# AHUS-P baseline (test.RunAHUSP), and writes timestamped logs to results/logs/.
#
# USAGE (parallel scenarios SC1-SC6):
#   ./run_full.sh                 # compile + run ALL scenarios SC1..SC6
#   ./run_full.sh SC2 SC6         # run only SC2 and SC6
#     SC1=seq vs parallel, SC2=thread scalability, SC3=parallel-technique ablation,
#     SC4=preprocessing scalability, SC5=data scalability, SC6=vs AHUS-P baseline
#   ./run_full.sh test            # run the TEST suite (small parameters) for a quick check
#   ./run_full.sh compile         # compile only
#
# BACKGROUND EXECUTION (recommended for long multi-hour runs):
#   nohup ./run_full.sh > /dev/null 2>&1 &     # logs are still written to results/logs/
#
# JVM CUSTOMIZATION via environment variable (default follows experiment.tex):
#   JVM_OPTS="-Xms8g -Xmx24g -Xss64m" ./run_full.sh SC2
#
set -u

# ----- Project root (supports paths with spaces) -----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR" || { echo "Cannot enter project directory"; exit 1; }

SRC_DIR="src"
OUT_DIR="out"
RESULTS_DIR="results"
LOG_DIR="$RESULTS_DIR/logs"
JVM_OPTS="${JVM_OPTS:--Xms8g -Xmx24g -Xss64m}"

mkdir -p "$OUT_DIR" "$LOG_DIR"

ts()  { date "+%Y-%m-%d %H:%M:%S"; }
log() { echo "[$(ts)] $*"; }

# ----- Compilation -----
compile() {
  log "Compiling Java sources (JDK: $(java -version 2>&1 | head -1))..."
  local files
  files=$(find "$SRC_DIR" -name "*.java")
  if javac -d "$OUT_DIR" $files; then
    log "Compilation successful -> $OUT_DIR/"
  else
    log "Compilation failed. Aborting."
    exit 1
  fi
}

# ----- Run a main class and write a dedicated log -----
# $1 = label (used as the log filename prefix); remaining args = class + parameters
run_java() {
  local label="$1"; shift
  local logfile="$LOG_DIR/${label}_$(date +%Y%m%d_%H%M%S).log"
  log "Running: java $JVM_OPTS -cp $OUT_DIR $*"
  log "         log -> $logfile"
  local start=$SECONDS
  # tee: write simultaneously to console and log file
  java $JVM_OPTS -cp "$OUT_DIR" "$@" 2>&1 | tee "$logfile"
  local rc=${PIPESTATUS[0]}
  local dur=$(( SECONDS - start ))
  if [ "$rc" -eq 0 ]; then
    log "Finished [$label] after ${dur}s (rc=0)"
  else
    log "Error [$label] after ${dur}s (rc=$rc) -- see $logfile"
  fi
  return "$rc"
}

# ----- Argument parsing -----
MODE="full"
ARGS=()
if [ "$#" -gt 0 ]; then
  case "$(echo "$1" | tr '[:upper:]' '[:lower:]')" in
    compile) MODE="compile" ;;
    test)    MODE="test"; shift; ARGS=("$@") ;;
    *)       MODE="full"; ARGS=("$@") ;;   # SC1..SC6 (or empty = all)
  esac
fi

log "===== RegHUSP-Parallel -- Official Experiment Suite ====="
log "Mode: $MODE | JVM_OPTS: $JVM_OPTS | logical cores: $(getconf _NPROCESSORS_ONLN 2>/dev/null || echo '?')"

compile

case "$MODE" in
  compile)
    log "Compile-only -- done."
    ;;
  test)
    # Small-parameter TEST suite. ${ARGS[@]+...}: safe expansion when array is empty under set -u (bash 3.2)
    run_java "RunTest" test.RunTest ${ARGS[@]+"${ARGS[@]}"}
    ;;
  full)
    # RunFull runs all SCs when given no arguments, or filters by the SC names passed in
    run_java "RunFull" test.RunFull ${ARGS[@]+"${ARGS[@]}"}
    ;;
esac

log "===== Done. CSV results and summary in $RESULTS_DIR/, logs in $LOG_DIR/ ====="
