#!/usr/bin/env bash
# Runs the built-in least-queued routing and the class-objective routing.
#
# Usage: examples/03-class-fair-share/run.sh [output root]
#
# The default output root is examples/03-class-fair-share/results, which holds the captures committed
# with this example and is rewritten by this script.
set -eu
. "$(dirname "$0")/../common.sh"
OUT=${1:-examples/03-class-fair-share/results}

for name in ex03-least-queued ex03-class-objective; do
	dest="$OUT/$name"
	mkdir -p "$dest"
	echo "== $name"
	docket -o "$OUT" "examples/03-class-fair-share/scenarios/$name.properties" > "$dest/console.txt" 2>&1 || {
		echo "FAILED $name, see $dest/console.txt" >&2
		exit 1
	}
	grep -E "^mean over" "$dest/console.txt" | sed "s/^/   /"
	keep_artifacts "$dest"
done
