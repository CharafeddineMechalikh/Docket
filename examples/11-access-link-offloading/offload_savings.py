"""Reports how many requests crossed the bearer and what the transfer cost them.

Offloading to a remote accelerator only pays if the prompt reaches it faster than the local machine
would have answered it, so the claim the policy makes is about the split between requests that cross
the link and requests that stay on site, and about the delay the crossing adds. This script reads the
per-request table of a finished run and the scenario that produced it, maps every replica id to the
device family the scenario declared, and reports for each run how many requests were offloaded, their
mean uplink time, and the first-token and end-to-end latency of the offloaded and the local requests
separately.

    python examples/11-access-link-offloading/offload_savings.py <results-dir> <scenario.properties> ...

Each pair is one run: a directory holding rep01/requests.csv ... exactly as the CLI writes it, and
the properties file that configured it.
"""

import csv
import os
import sys

REMOTE = "cloud-accelerator"
LOCAL = "edge-datacenter"


def tiers_from_scenario(path):
    """Maps replica id to the device family the scenario declares for the machine it runs on."""
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


def summary(root, tier_of_replica):
    """Collects the counts and the mean of a numeric column, over every repetition."""
    placed = 0
    refused = 0
    groups = {}
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
                placed += 1
                tier = tier_of_replica[int(replica)]
                bucket = groups.setdefault(tier, {"n": 0, "uplink": 0.0, "ttft": 0.0, "e2e": 0.0})
                bucket["n"] += 1
                for column, key in (("uplink_s", "uplink"), ("ttft_s", "ttft"), ("e2e_s", "e2e")):
                    if row[column].strip():
                        bucket[key] += float(row[column])
    return placed, refused, groups


def mean(value, n):
    return value / n if n else float("nan")


def main():
    args = sys.argv[1:]
    if len(args) < 2 or len(args) % 2 != 0:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    for root, scenario in zip(args[0::2], args[1::2]):
        label = os.path.basename(root.rstrip("/\\"))
        placed, refused, groups = summary(root, tiers_from_scenario(scenario))
        remote = groups.get(REMOTE, {"n": 0, "uplink": 0.0, "ttft": 0.0, "e2e": 0.0})
        local = groups.get(LOCAL, {"n": 0, "uplink": 0.0, "ttft": 0.0, "e2e": 0.0})
        print("== %s" % label)
        print("   placed %d, refused %d" % (placed, refused))
        share = 100.0 * remote["n"] / placed if placed else 0.0
        print("   %-9s offloaded %3d (%4.1f%%)  uplink %6.3f s  ttft %6.3f s  e2e %6.3f s"
              % ("remote", remote["n"], share, mean(remote["uplink"], remote["n"]),
                 mean(remote["ttft"], remote["n"]), mean(remote["e2e"], remote["n"])))
        print("   %-9s on site    %3d (%4.1f%%)  uplink %6.3f s  ttft %6.3f s  e2e %6.3f s"
              % ("local", local["n"], 100.0 * local["n"] / placed if placed else 0.0,
                 mean(local["uplink"], local["n"]), mean(local["ttft"], local["n"]),
                 mean(local["e2e"], local["n"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
