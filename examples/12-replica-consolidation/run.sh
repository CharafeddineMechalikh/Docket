#!/usr/bin/env bash
# Runs a load balancer and the two consolidation rules over one fleet of identical replicas, so the
# only thing that changes is how full each batch runs and how many machines leave their idle power.
#
# Usage: examples/12-replica-consolidation/run.sh [output root]
#
# The default output root is examples/12-replica-consolidation/results, which holds the captures
# committed with this example and is rewritten by this script.
set -eu
. "$(dirname "$0")/../common.sh"
OUT=${1:-examples/12-replica-consolidation/results}
SCEN=examples/12-replica-consolidation/scenarios
NAMES="ex12-least-queued ex12-consolidate ex12-consolidate-with-headroom"

for name in $NAMES; do
	dest="$OUT/$name"
	mkdir -p "$dest"
	echo "== $name"
	docket -o "$OUT" "$SCEN/$name.properties" > "$dest/console.txt" 2>&1 || {
		echo "FAILED $name, see $dest/console.txt" >&2
		exit 1
	}
	grep -E "^mean over" "$dest/console.txt" | sed "s/^/   /"
done

# The consolidation claim is about how many replicas a rule kept busy, so it is read from the
# per-request table before that table is dropped.
ARGS=""
for name in $NAMES; do
	ARGS="$ARGS $OUT/$name"
done
python examples/12-replica-consolidation/consolidation.py $ARGS

# Several repetitions run, so keep the aggregate artifacts and drop every per-request table.
for name in $NAMES; do
	rm -rf "$OUT/$name"/rep*
done
