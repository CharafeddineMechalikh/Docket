#!/usr/bin/env bash
# Runs the unprotected and the protected admission policies.
#
# Usage: examples/02-sla-admission-protection/run.sh [output root]
#
# The default output root is examples/02-sla-admission-protection/results, which holds the captures committed
# with this example and is rewritten by this script.
set -eu
. "$(dirname "$0")/../common.sh"
OUT=${1:-examples/02-sla-admission-protection/results}

for name in ex02-unlimited ex02-reservation; do
	dest="$OUT/$name"
	mkdir -p "$dest"
	echo "== $name"
	docket -o "$OUT" "examples/02-sla-admission-protection/scenarios/$name.properties" > "$dest/console.txt" 2>&1 || {
		echo "FAILED $name, see $dest/console.txt" >&2
		exit 1
	}
	grep -E "^mean over" "$dest/console.txt" | sed "s/^/   /"
	keep_artifacts "$dest"
done
