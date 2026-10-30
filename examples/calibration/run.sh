#!/usr/bin/env bash
# Replays the measured calibration scenario.
#
# Usage: examples/calibration/run.sh [output root]
#
# The default output root is examples/calibration/results, which holds the captures committed
# with this example and is rewritten by this script.
set -eu
. "$(dirname "$0")/../common.sh"
OUT=${1:-examples/calibration/results}

name=laptop-cpu-qwen05b
dest="$OUT/$name"
mkdir -p "$dest"
echo "== $name"
docket -o "$OUT" "examples/calibration/scenarios/$name.properties" > "$dest/console.txt" 2>&1 || {
	echo "FAILED $name, see $dest/console.txt" >&2
	exit 1
}
grep -E "^mean over|^overall provenance|^cost model" "$dest/console.txt" | sed "s/^/   /"
keep_artifacts "$dest"
