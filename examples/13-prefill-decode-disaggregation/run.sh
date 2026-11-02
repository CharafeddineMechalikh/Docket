#!/usr/bin/env bash
# Runs one workload over three deployments of the same four-device budget: colocated serving,
# disaggregated serving on identical cards, and disaggregated serving on specialised cards. The only
# thing that changes between the three is how the two phases share the machines, so the difference in
# the per-token latency, the first-token queue and the batch shape is the effect of separation.
#
# Usage: examples/13-prefill-decode-disaggregation/run.sh [output root]
#
# The default output root is examples/13-prefill-decode-disaggregation/results, which holds the
# captures committed with this example and is rewritten by this script.
set -eu
. "$(dirname "$0")/../common.sh"
OUT=${1:-examples/13-prefill-decode-disaggregation/results}
SCEN=examples/13-prefill-decode-disaggregation/scenarios
NAMES="ex13-colocated ex13-pd-homogeneous ex13-pd-heterogeneous"

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

# Several repetitions run, so keep the aggregate artifacts and drop every per-request table.
for name in $NAMES; do
	rm -rf "$OUT/$name"/rep*
done
