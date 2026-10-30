#!/usr/bin/env bash
# Runs the three built-in scheduling policies on the dense trace.
#
# Usage: examples/08-policy-sweep/run.sh [output root]
#
# The default output root is examples/08-policy-sweep/results, which holds the captures committed
# with this example and is rewritten by this script.
set -eu
. "$(dirname "$0")/../common.sh"
OUT=${1:-examples/08-policy-sweep/results}

for name in ex08-fcfs ex08-priority ex08-shortest-expected-first; do
	dest="$OUT/$name"
	mkdir -p "$dest"
	echo "== $name"
	docket -o "$OUT" "examples/08-policy-sweep/scenarios/$name.properties" > "$dest/console.txt" 2>&1 || {
		echo "FAILED $name, see $dest/console.txt" >&2
		exit 1
	}
	grep -E "^mean over" "$dest/console.txt" | sed "s/^/   /"
	keep_artifacts "$dest"
done
