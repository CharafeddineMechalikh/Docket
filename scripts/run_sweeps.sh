#!/usr/bin/env bash
# Re-runs every measured configuration quoted in the paper, with three repetitions each.
#
# Usage: scripts/run_sweeps.sh [output root]
#
# The default output root is target/sweep. Each configuration writes to
# <root>/<name>/repNN, which is the layout scripts/plot_sweeps.py reads.
set -u
cd "$(dirname "$0")/.."
OUT=${1:-target/sweep}
JAR=target/genai-inference-sim-0.1.0.jar
LOADED=scenarios/loaded-7b-memory-pressure.properties
LIGHT=scenarios/single-gpu-poisson.properties
fail=0

run() { # name scenario extra...
  local name=$1 scenario=$2; shift 2
  java -jar "$JAR" -q -r 3 -o "$OUT/$name" -D "experiment.name=$name" "$@" "$scenario" \
    || { echo "FAILED $name"; fail=1; }
}

for r in 15 30 60 120 240 480; do run "rate$r" "$LOADED" -D "workload.ratePerMinute=$r"; done
for b in 4 8 16 32 64; do run "batch$b" "$LOADED" -D "replica.maxBatchSize=$b"; done
for c in 0 128 512 2048; do run "chunk$c" "$LOADED" -D "replica.maxPrefillChunkTokens=$c"; done
for k in 8 16 32; do run "block$k" "$LOADED" -D "replica.tokensPerBlock=$k"; done
run "nopreempt" "$LOADED" -D "replica.allowPreemption=false"

java -jar "$JAR" -q -r 3 -o "$OUT/baseline/light" -D "experiment.name=light" "$LIGHT" || fail=1
java -jar "$JAR" -q -r 3 -o "$OUT/baseline/loaded" -D "experiment.name=loaded" "$LOADED" || fail=1

echo "SWEEP_DONE fail=$fail"
