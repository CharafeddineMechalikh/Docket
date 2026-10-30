#!/usr/bin/env bash
# Runs the two composite orders and the unlimited baseline.
#
# Usage: examples/06-composite-admission/run.sh [output root]
#
# The default output root is examples/06-composite-admission/results, which holds the captures committed
# with this example and is rewritten by this script.
set -eu
. "$(dirname "$0")/../common.sh"
OUT=${1:-examples/06-composite-admission/results}

for name in ex06-unlimited ex06-cache-then-queue ex06-queue-then-cache; do
	dest="$OUT/$name"
	mkdir -p "$dest"
	echo "== $name"
	docket -o "$OUT" "examples/06-composite-admission/scenarios/$name.properties" > "$dest/console.txt" 2>&1 || {
		echo "FAILED $name, see $dest/console.txt" >&2
		exit 1
	}
	grep -E "^mean over" "$dest/console.txt" | sed "s/^/   /"
	keep_artifacts "$dest"
done
