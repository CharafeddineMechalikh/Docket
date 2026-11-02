#!/usr/bin/env bash
# Runs the four placement rules over the same three-tier fleet: two built-in load balancers that
# treat the machines as interchangeable, and the two edge-first rules that prefer the cheap tier.
#
# Usage: examples/10-edge-first-placement/run.sh [output root]
#
# The default output root is examples/10-edge-first-placement/results, which holds the captures
# committed with this example and is rewritten by this script.
set -eu
. "$(dirname "$0")/../common.sh"
OUT=${1:-examples/10-edge-first-placement/results}

for name in ex10-least-queued ex10-least-predicted-work ex10-edge-first ex10-edge-first-within-budget; do
	dest="$OUT/$name"
	mkdir -p "$dest"
	echo "== $name"
	docket -o "$OUT" "examples/10-edge-first-placement/scenarios/$name.properties" > "$dest/console.txt" 2>&1 || {
		echo "FAILED $name, see $dest/console.txt" >&2
		exit 1
	}
	grep -E "^mean over" "$dest/console.txt" | sed "s/^/   /"
done

# The edge-first claim is about which tier the requests land on, so it is read from the per-request
# table before that table is dropped.
SCEN=examples/10-edge-first-placement/scenarios
python examples/10-edge-first-placement/tier_placement.py \
	"$OUT/ex10-least-queued" "$SCEN/ex10-least-queued.properties" \
	"$OUT/ex10-least-predicted-work" "$SCEN/ex10-least-predicted-work.properties" \
	"$OUT/ex10-edge-first" "$SCEN/ex10-edge-first.properties" \
	"$OUT/ex10-edge-first-within-budget" "$SCEN/ex10-edge-first-within-budget.properties"

# This is the first example that runs several repetitions, so it keeps the aggregate artifacts and
# drops every per-request table, not only rep01. The pooled summary and SLO rows already hold the
# numbers the README quotes.
for name in ex10-least-queued ex10-least-predicted-work ex10-edge-first ex10-edge-first-within-budget; do
	rm -rf "$OUT/$name"/rep*
done
