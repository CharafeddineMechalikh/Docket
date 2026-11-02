"""Reports how many replicas a run actually kept busy and what that cost in energy.

Consolidation is a claim about the machines, not the aggregate throughput: a balancer spreads the
same traffic over more replicas, each running a shallow batch, while a packing rule fills one replica
before opening the next, so fewer devices ever leave their idle power. This script reads the
per-request table of a finished run to see how concentrated the placement really was (how many
replicas received work, and the share the busiest one carried), and reads the pooled summary for the
energy and the batch size that concentration bought.

    python examples/12-replica-consolidation/consolidation.py <results-dir> ...

Each argument is a results directory holding rep01/requests.csv and summary.csv, exactly as the CLI
writes them.
"""

import csv
import os
import sys


def pooled(summary_path):
    """Averages the per-repetition columns the summary table already reports."""
    with open(summary_path, newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    if not rows:
        return None

    def mean(column):
        return sum(float(row[column]) for row in rows) / len(rows)

    return {
        "goodput": mean("goodput"),
        "ttft": mean("ttft_s_mean"),
        "energy_wh": mean("energy_wh"),
        "utilisation": mean("device_utilisation"),
        "peak_batch": max(int(row["peak_batch"]) for row in rows),
        "replicas": int(rows[0]["replicas"]),
    }


def placement(root):
    """Tallies how many requests each replica received, over every repetition."""
    counts = {}
    refused = 0
    for name in sorted(os.listdir(root)):
        rep = os.path.join(root, name)
        path = os.path.join(rep, "requests.csv")
        if not os.path.isdir(rep) or not os.path.exists(path):
            continue
        with open(path, newline="", encoding="utf-8") as handle:
            for row in csv.DictReader(handle):
                replica = row["replica_id"].strip()
                if not replica:
                    refused += 1
                    continue
                counts[int(replica)] = counts.get(int(replica), 0) + 1
    total = sum(counts.values())
    active = sum(1 for value in counts.values() if value > 0)
    top = max(counts.values()) if counts else 0
    return total, refused, active, top, counts


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    for root in args:
        label = os.path.basename(root.rstrip("/\\"))
        summary = pooled(os.path.join(root, "summary.csv"))
        total, refused, active, top, counts = placement(root)
        print("== %s" % label)
        if summary is None:
            print("   no summary.csv, run the scenario first", file=sys.stderr)
            continue
        print("   goodput %.3f  ttft %.3f s  energy %.1f Wh  util %.1f%%  peak batch %d  (%d replicas)"
              % (summary["goodput"], summary["ttft"], summary["energy_wh"], 100.0 * summary["utilisation"],
                 summary["peak_batch"], summary["replicas"]))
        share = 100.0 * top / total if total else 0.0
        print("   replicas that received work: %d of %d   busiest carries %.1f%% of requests (refused %d)"
              % (active, summary["replicas"], share, refused))
        layout = " ".join("%d:%d" % (replica, counts.get(replica, 0))
                          for replica in range(summary["replicas"]))
        print("   requests per replica  %s" % layout)
    return 0


if __name__ == "__main__":
    sys.exit(main())
