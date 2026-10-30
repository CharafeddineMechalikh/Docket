#!/usr/bin/env bash
# Runs the built-in least-queued routing and the weighted routing.
#
# Usage: examples/05-weighted-load-routing/run.sh [output root]
#
# The default output root is examples/05-weighted-load-routing/results, which holds the captures committed
# with this example and is rewritten by this script.
set -eu
. "$(dirname "$0")/../common.sh"
OUT=${1:-examples/05-weighted-load-routing/results}

for name in ex05-least-queued ex05-weighted; do
	dest="$OUT/$name"
	mkdir -p "$dest"
	echo "== $name"
	docket -o "$OUT" "examples/05-weighted-load-routing/scenarios/$name.properties" > "$dest/console.txt" 2>&1 || {
		echo "FAILED $name, see $dest/console.txt" >&2
		exit 1
	}
	grep -E "^mean over" "$dest/console.txt" | sed "s/^/   /"
	keep_artifacts "$dest"
done
