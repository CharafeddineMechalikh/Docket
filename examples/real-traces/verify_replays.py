#!/usr/bin/env python3
"""Reconcile a finished replay against the trace it was run from.

The simulator writes one row per request in repNN/requests.csv, in completion order. This script
matches those rows against the recorded window as a multiset of (arrival, prompt, generated
length, service class) and reports the largest arrival difference and every row that has no
counterpart. It is the check that the replay released the recorded instants, kept the recorded
lengths and did not lose or duplicate a request; the queueing and latency columns are the
simulator's output, not part of the comparison.

Usage:
    python examples/real-traces/verify_replays.py [--results DIR] [--data DIR]

The default results root is target/trace-replays, the directory run_replays.sh writes.
"""

import argparse
import csv
import sys
from collections import Counter
from pathlib import Path

TRACES = ("azure-conv", "qwen-traceA", "kimi-conversation", "burstgpt")

# Arrivals are written with three decimals by the converter and nine by the recorder.
ARRIVAL_TOLERANCE_S = 1e-6


def read_trace(path):
    rows = []
    with open(path, encoding="utf-8", newline="") as handle:
        for record in csv.DictReader(handle):
            rows.append((float(record["arrival_time_s"]), int(record["input_tokens"]),
                         int(record["output_tokens"]), record["service_class"]))
    return rows


def read_replay(path):
    rows = []
    with open(path, encoding="utf-8", newline="") as handle:
        for record in csv.DictReader(handle):
            rows.append((float(record["arrival_time_s"]), int(record["input_tokens"]),
                         int(record["output_tokens_requested"]), record["service_class"]))
    return rows


def reconcile(name, data, results):
    trace = read_trace(data / (name + ".csv"))
    replay_dir = results / name / "rep01"
    requests = replay_dir / "requests.csv"
    if not requests.exists():
        print("%-18s MISSING %s" % (name, requests))
        return False
    replay = read_replay(requests)
    if len(trace) != len(replay):
        print("%-18s FAILED %d rows recorded, %d rows replayed" % (name, len(trace), len(replay)))
        return False
    # Arrival instants are compared by position after sorting, so the largest difference is the
    # engine clock's, not the completion order's.
    by_arrival = sorted(replay)
    differences = [abs(recorded[0] - replayed[0]) for recorded, replayed in zip(sorted(trace), by_arrival)]
    unmatched = Counter(trace) - Counter(replay)
    extra = Counter(replay) - Counter(trace)
    worst = max(differences) if differences else 0.0
    status = "OK" if worst <= ARRIVAL_TOLERANCE_S and not unmatched and not extra else "FAILED"
    print("%-18s %s rows=%d max_arrival_diff=%.3g s unmatched=%d extra=%d" %
          (name, status, len(trace), worst, sum(unmatched.values()), sum(extra.values())))
    for row in list(unmatched)[:3]:
        print("    recorded but not replayed: arrival=%.3f input=%d output=%d class=%s" % row)
    for row in list(extra)[:3]:
        print("    replayed but not recorded: arrival=%.3f input=%d output=%d class=%s" % row)
    return status == "OK"


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--results", default="target/trace-replays",
                        help="root holding <trace>/rep01/requests.csv (default: target/trace-replays)")
    parser.add_argument("--data", default="examples/real-traces/data",
                        help="directory holding the converted trace windows")
    args = parser.parse_args()
    data = Path(args.data)
    results = Path(args.results)
    ok = True
    for name in TRACES:
        ok = reconcile(name, data, results) and ok
    print("REPLAY_CHECK %s" % ("OK" if ok else "FAILED"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
