#!/usr/bin/env bash
# Runs the template policy, which reproduces the built-in first-come order.
#
# Usage: examples/09-custom-policy-template/run.sh [output root]
#
# The default output root is examples/09-custom-policy-template/results, which holds the captures committed
# with this example and is rewritten by this script.
set -eu
. "$(dirname "$0")/../common.sh"
OUT=${1:-examples/09-custom-policy-template/results}

for name in ex09-template; do
	dest="$OUT/$name"
	mkdir -p "$dest"
	echo "== $name"
	docket -o "$OUT" "examples/09-custom-policy-template/scenarios/$name.properties" > "$dest/console.txt" 2>&1 || {
		echo "FAILED $name, see $dest/console.txt" >&2
		exit 1
	}
	grep -E "^mean over" "$dest/console.txt" | sed "s/^/   /"
	keep_artifacts "$dest"
done
