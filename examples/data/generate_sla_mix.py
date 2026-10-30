"""Generates the two synthetic traces the policy examples replay.

The traces are committed, so a reader never has to run this script to run an example. It is kept
so that the numbers in the CSV can be regenerated from a seed rather than trusted as constants.

Both files use the column names of the replay loader (see docs/traces.md). The workloads are
synthetic: the arrival instants, prompt lengths and output lengths are drawn from simple
distributions, not recorded from a deployment.

    python examples/data/generate_sla_mix.py

Writes examples/data/sla-mix.csv and examples/data/session-chats.csv.
"""

import argparse
import csv
import os
import random

HEADER = [
    "arrival_time_s",
    "model_id",
    "input_tokens",
    "output_tokens",
    "priority",
    "service_class",
    "session_id",
    "ttft_target_s",
    "e2e_target_s",
    "tpot_target_s",
]

MODEL = "sla-mix"

# Two classes with different shapes and different objectives. The targets are stated per class
# rather than in the scenario, because a scenario holds one workload and the SLO table is built
# from the targets carried by each row.
INTERACTIVE = {
    "class": "interactive",
    "priority": 10,
    "input": (192, 768),
    "output": (32, 192),
    "ttft": 1.5,
    "e2e": 15.0,
    "tpot": 0.05,
}

BATCH = {
    "class": "batch",
    "priority": 0,
    "input": (1024, 4096),
    "output": (256, 1024),
    "ttft": 15.0,
    "e2e": 300.0,
    "tpot": 0.20,
}


def row(arrival, spec, session="", input_tokens=None, output_tokens=None):
    if input_tokens is None:
        input_tokens = random.randint(*spec["input"])
    if output_tokens is None:
        output_tokens = random.randint(*spec["output"])
    return [
        "%.3f" % arrival,
        MODEL,
        input_tokens,
        output_tokens,
        spec["priority"],
        spec["class"],
        session,
        "%.2f" % spec["ttft"],
        "%.2f" % spec["e2e"],
        "%.3f" % spec["tpot"],
    ]


def write(path, rows):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(HEADER)
        writer.writerows(rows)


def sla_mix(rows, mean_gap, interactive_share):
    """Poisson arrivals, 30 percent interactive by default, over one trace window."""
    out = []
    arrival = 0.0
    for _ in range(rows):
        arrival += random.expovariate(1.0 / mean_gap)
        spec = INTERACTIVE if random.random() < interactive_share else BATCH
        out.append(row(arrival, spec))
    return out


def session_chats(sessions, turns, gap):
    """Conversations of several turns, each turn reusing the session identifier.

    The simulator does not model shared-prefix reuse, so this trace exists to exercise routing
    state, not to claim a cache saving: a policy can observe that the turns belong together.
    """
    out = []
    for session in range(sessions):
        start = random.uniform(0, 10)
        arrival = start
        for turn in range(turns):
            prompt = min(4096, 512 + 128 * turn)
            out.append(row(arrival, INTERACTIVE, "chat-%03d" % session, prompt,
                           random.randint(*INTERACTIVE["output"])))
            arrival += gap + random.uniform(0, 0.5)
    out.sort(key=lambda r: float(r[0]))
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", type=int, default=20261030)
    parser.add_argument("--rows", type=int, default=600)
    parser.add_argument("--interactive-share", type=float, default=0.30)
    parser.add_argument("--out-dir", default="examples/data")
    args = parser.parse_args()

    random.seed(args.seed)
    mix = sla_mix(args.rows, 0.28, args.interactive_share)
    write(os.path.join(args.out_dir, "sla-mix.csv"), mix)
    print("sla-mix.csv: %d rows, %.1f s, %d interactive" %
          (len(mix), float(mix[-1][0]), sum(1 for r in mix if r[5] == "interactive")))

    random.seed(args.seed + 1)
    dense = sla_mix(args.rows, 0.07, args.interactive_share)
    write(os.path.join(args.out_dir, "sla-mix-dense.csv"), dense)
    print("sla-mix-dense.csv: %d rows, %.1f s, %d interactive" %
          (len(dense), float(dense[-1][0]), sum(1 for r in dense if r[5] == "interactive")))

    random.seed(args.seed + 2)
    chats = session_chats(80, 5, 0.45)
    write(os.path.join(args.out_dir, "session-chats.csv"), chats)
    print("session-chats.csv: %d rows, %.1f s, 80 sessions" % (len(chats), float(chats[-1][0])))


if __name__ == "__main__":
    main()
