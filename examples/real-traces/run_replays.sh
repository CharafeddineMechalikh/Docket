#!/usr/bin/env bash
# Replays the four committed trace windows and checks them against the recordings.
#
# Usage: examples/real-traces/run_replays.sh [output root]
#
# The default output root is target/trace-replays. Each scenario writes
# <root>/<name>/rep01/requests.csv, which examples/real-traces/verify_replays.py reconciles
# with examples/real-traces/data/<name>.csv, so the check only passes when every recorded
# request was released at its recorded instant with its recorded prompt and output length.
set -u
cd "$(dirname "$0")/../.."
OUT=${1:-target/trace-replays}
JAR=target/docket-1.0.1.jar
TRACES=(azure-conv qwen-traceA kimi-conversation burstgpt)
fail=0

mkdir -p "$OUT"
for name in "${TRACES[@]}"; do
  echo "== $name"
  rm -rf "$OUT/$name"
  java -jar "$JAR" -o "$OUT" "examples/real-traces/scenarios/$name.properties" \
    > "$OUT/$name.console.txt" 2>&1
  status=$?
  grep -E '^rep ' -A 1 "$OUT/$name.console.txt" | tail -n 1
  [ $status -eq 0 ] || { echo "FAILED $name (exit $status, see $OUT/$name.console.txt)"; fail=1; }
done

python examples/real-traces/verify_replays.py --results "$OUT" || fail=1
echo "REPLAYS_DONE fail=$fail"
exit $fail
