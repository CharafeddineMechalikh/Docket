#!/usr/bin/env bash
# Runs the same scenario with and without aborting on a missed deadline.
#
# Usage: examples/07-deadline-abort/run.sh [output root]
#
# The default output root is examples/07-deadline-abort/results, which holds the captures committed
# with this example and is rewritten by this script.
set -eu
. "$(dirname "$0")/../common.sh"
OUT=${1:-examples/07-deadline-abort/results}

for name in ex07-abort-off ex07-abort-on; do
	dest="$OUT/$name"
	mkdir -p "$dest"
	echo "== $name"
	docket -o "$OUT" "examples/07-deadline-abort/scenarios/$name.properties" > "$dest/console.txt" 2>&1 || {
		echo "FAILED $name, see $dest/console.txt" >&2
		exit 1
	}
	grep -E "^mean over" "$dest/console.txt" | sed "s/^/   /"
	keep_artifacts "$dest"
done
