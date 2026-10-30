#!/usr/bin/env bash
# Runs the built-in least-queued routing and the session affinity routing.
#
# Usage: examples/04-session-affinity/run.sh [output root]
#
# The default output root is examples/04-session-affinity/results, which holds the captures committed
# with this example and is rewritten by this script.
set -eu
. "$(dirname "$0")/../common.sh"
OUT=${1:-examples/04-session-affinity/results}

for name in ex04-least-queued ex04-affinity; do
	dest="$OUT/$name"
	mkdir -p "$dest"
	echo "== $name"
	docket -o "$OUT" "examples/04-session-affinity/scenarios/$name.properties" > "$dest/console.txt" 2>&1 || {
		echo "FAILED $name, see $dest/console.txt" >&2
		exit 1
	}
	grep -E "^mean over" "$dest/console.txt" | sed "s/^/   /"
done

# The affinity claim is about where the turns of one conversation land, not about latency, so it
# is read from the per-request table before that table is dropped.
python examples/04-session-affinity/session_locality.py \
	"$OUT/ex04-least-queued" "$OUT/ex04-affinity"
for name in ex04-least-queued ex04-affinity; do
	keep_artifacts "$OUT/$name"
done
