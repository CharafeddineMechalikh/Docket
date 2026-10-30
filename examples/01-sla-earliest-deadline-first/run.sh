#!/usr/bin/env bash
# Runs the three scheduling policies of this example.
#
# Usage: examples/01-sla-earliest-deadline-first/run.sh [output root]
#
# The default output root is examples/01-sla-earliest-deadline-first/results, which holds the captures committed
# with this example and is rewritten by this script.
set -eu
. "$(dirname "$0")/../common.sh"
OUT=${1:-examples/01-sla-earliest-deadline-first/results}

for name in ex01-fcfs ex01-priority ex01-edf; do
	dest="$OUT/$name"
	mkdir -p "$dest"
	echo "== $name"
	docket -o "$OUT" "examples/01-sla-earliest-deadline-first/scenarios/$name.properties" > "$dest/console.txt" 2>&1 || {
		echo "FAILED $name, see $dest/console.txt" >&2
		exit 1
	}
	grep -E "^mean over" "$dest/console.txt" | sed "s/^/   /"
	keep_artifacts "$dest"
done
