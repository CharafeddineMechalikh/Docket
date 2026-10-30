"""Reports how the turns of one conversation spread over the replicas.

The affinity policy does not make the simulator faster here: Docket does not model shared-prefix
reuse, so a turn that returns to the replica of the previous turn reads no cached tokens. What
the policy changes is where the turn lands, which is what this script measures from the
per-request table of a finished run.

    python examples/04-session-affinity/session_locality.py <results/ex04-least-queued> ...

Each argument is a results directory holding rep01/requests.csv, exactly as the CLI writes it.
"""

import csv
import os
import sys


def locality(root):
    path = os.path.join(root, "rep01", "requests.csv")
    with open(path, newline="", encoding="utf-8") as handle:
        rows = sorted(csv.DictReader(handle),
                      key=lambda row: (row["session_id"], float(row["arrival_time_s"])))
    previous = {}
    consecutive = 0
    turns_after_first = 0
    replicas = {}
    for row in rows:
        session = row["session_id"]
        replica = row["replica_id"]
        replicas.setdefault(session, set()).add(replica)
        if session in previous:
            turns_after_first += 1
            if previous[session] == replica:
                consecutive += 1
        previous[session] = replica
    mean_spread = sum(len(seen) for seen in replicas.values()) / len(replicas)
    return consecutive, turns_after_first, len(replicas), mean_spread


def main():
    if len(sys.argv) < 2:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    print("%-20s %9s %9s %9s %9s" % ("run", "turns", "same", "share", "replicas/session"))
    for root in sys.argv[1:]:
        consecutive, turns, sessions, spread = locality(root)
        print("%-20s %9d %9d %9.3f %9.2f" % (os.path.basename(root.rstrip("/\\")), turns,
                                             consecutive, consecutive / turns, spread))
    return 0


if __name__ == "__main__":
    sys.exit(main())
