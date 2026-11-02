#!/usr/bin/env bash
# Runs the capacity-blind balancer and the prompt-size offload rule over a thin bearer and a fat one,
# so the transfer tax the two machines differ by is the thing under test.
#
# Usage: examples/11-access-link-offloading/run.sh [output root]
#
# The default output root is examples/11-access-link-offloading/results, which holds the captures
# committed with this example and is rewritten by this script.
set -eu
. "$(dirname "$0")/../common.sh"
OUT=${1:-examples/11-access-link-offloading/results}
SCEN=examples/11-access-link-offloading/scenarios
NAMES="ex11-least-predicted-work ex11-offload-small-prompts ex11-least-predicted-work-fastlink ex11-offload-small-prompts-fastlink"

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

# The offload claim is about which requests crossed the bearer and what it cost them, so it is read
# from the per-request table before that table is dropped.
ARGS=""
for name in $NAMES; do
	ARGS="$ARGS $OUT/$name $SCEN/$name.properties"
done
python examples/11-access-link-offloading/offload_savings.py $ARGS

# Several repetitions run, so keep the aggregate artifacts and drop every per-request table.
for name in $NAMES; do
	rm -rf "$OUT/$name"/rep*
done
