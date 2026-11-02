"""Reports which device tier each request was placed on, and what the placement cost there.

The edge-first policies prefer a tier over a machine, so the claim they make is not about the
aggregate throughput but about where the requests land and how long the ones sent downward wait
for it. This script reads the per-request table of a finished run and the scenario that produced
it, maps every replica id to the device family the scenario declared for that replica, and prints
the share of placed requests each tier served together with the first-token time they saw there.

    python examples/10-edge-first-placement/tier_placement.py <results-dir> <scenario.properties> ...

Each pair is one run: a directory holding rep01/requests.csv ... exactly as the CLI writes it, and
the properties file that configured it.
"""

import csv
import os
import sys

PREFERENCE = ["edge-device", "edge-datacenter", "cloud-accelerator"]


def tiers_from_scenario(path):
    """Maps replica id to the device family the scenario declares for the machine it runs on.

    A fleet names one profile per replica, in order, and a profile names its family with
    ``device.<profile>.type``. A single device scenario has no per-replica list, so every replica
    shares the one family named by ``device.type``.
    """
    profiles = None
    count = 1
    device_type = {}
    flat_type = "accelerator"
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            key = key.strip()
            if key == "replicas":
                profiles = [name.strip() for name in value.split(",")]
            elif key == "replicas.count":
                count = int(value)
            elif key == "device.type":
                flat_type = value.strip()
            elif key.startswith("device.") and key.endswith(".type"):
                device_type[key[len("device."):-len(".type")]] = value.strip()
    if profiles is None:
        return [flat_type] * count
    return [device_type.get(profile, profile) for profile in profiles]


def placement(root, tier_of_replica):
    """Tallies placed requests and their first-token time per tier, over every repetition."""
    placed = {}
    ttft = {}
    refused = 0
    for name in sorted(os.listdir(root)):
        path = os.path.join(root, name, "requests.csv")
        if not os.path.isdir(os.path.join(root, name)) or not os.path.exists(path):
            continue
        with open(path, newline="", encoding="utf-8") as handle:
            for row in csv.DictReader(handle):
                replica = row["replica_id"].strip()
                if not replica:
                    refused += 1
                    continue
                tier = tier_of_replica[int(replica)]
                placed[tier] = placed.get(tier, 0) + 1
                if row["ttft_s"].strip():
                    ttft.setdefault(tier, []).append(float(row["ttft_s"]))
    total = sum(placed.values())
    return total, refused, placed, ttft


def main():
    args = sys.argv[1:]
    if len(args) < 2 or len(args) % 2 != 0:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    for root, scenario in zip(args[0::2], args[1::2]):
        label = os.path.basename(root.rstrip("/\\"))
        tier_of_replica = tiers_from_scenario(scenario)
        total, refused, placed, ttft = placement(root, tier_of_replica)
        print("== %s" % label)
        print("   %-18s %7s %7s %14s" % ("tier", "placed", "share", "mean ttft (s)"))
        order = [tier for tier in PREFERENCE if tier in placed]
        order += [tier for tier in sorted(placed) if tier not in order]
        for tier in order:
            mean = sum(ttft[tier]) / len(ttft[tier]) if ttft.get(tier) else float("nan")
            share = 100.0 * placed[tier] / total if total else 0.0
            mean_cell = "%14.3f" % mean if ttft.get(tier) else "%14s" % "-"
            print("   %-18s %7d %6.1f%% %s" % (tier, placed[tier], share, mean_cell))
        edge = sum(placed.get(tier, 0) for tier in PREFERENCE[:1])
        print("   %-18s %7d %6.1f%%  (refused %d)"
              % ("edge-device share", edge, 100.0 * edge / total if total else 0.0, refused))
    return 0


if __name__ == "__main__":
    sys.exit(main())
