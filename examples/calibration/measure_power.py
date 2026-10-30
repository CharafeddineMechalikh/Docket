#!/usr/bin/env python3
"""Measure whole-system power from the battery while the laptop is unplugged.

The two coefficients Docket's energy model needs are the power the machine draws when the
replica is idle and when it is busy. On a laptop the battery reports its discharge rate in mW,
which is the whole system: the CPU, the screen and everything else. That is the right figure for
a single-device deployment of this profile, and the wrong figure for a GPU server, where the
device is one card among many.

Requirements:
    - Windows, with the laptop running on battery (the script refuses on AC power).
    - The llama.cpp build and the f16 model used by examples/calibration/README.md, under
      target/calibration/ by default.

Usage:
    python examples/calibration/measure_power.py idle --seconds 60
    python examples/calibration/measure_power.py load --seconds 90

Both write a JSON capture next to this file unless --no-write is given. The model and build paths
can be overridden with --model and --bench.
"""

import argparse
import json
import statistics
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent

SAMPLE_PS = r"""
$end = (Get-Date).AddSeconds({seconds})
while ((Get-Date) -lt $end) {{
    $b = Get-CimInstance -Namespace root\wmi -ClassName BatteryStatus -ErrorAction SilentlyContinue
    if ($b) {{ "$($b.PowerOnline),$($b.Discharging),$($b.DischargeRate)" }}
    Start-Sleep -Seconds 1
}}
"""


def samples(seconds):
    out = subprocess.run(
        ["powershell", "-NoProfile", "-Command", SAMPLE_PS.format(seconds=seconds)],
        capture_output=True, text=True, check=True,
    ).stdout
    rows = []
    for line in out.splitlines():
        parts = [p.strip() for p in line.strip().split(",")]
        if len(parts) != 3:
            continue
        try:
            power_online, discharging, rate_mw = parts[0].lower(), parts[1].lower(), int(parts[2])
        except ValueError:
            continue
        rows.append({"on_ac": power_online == "true", "discharging": discharging == "true",
                     "watts": rate_mw / 1000.0})
    return rows


def require_battery(rows):
    if not rows:
        sys.exit("no battery samples: this machine exposes no battery telemetry")
    if any(row["on_ac"] for row in rows):
        sys.exit("the laptop is on AC power; unplug it before measuring whole-system power")
    if any(not row["discharging"] for row in rows):
        sys.exit("the battery does not report discharging; plug status may be changing, retry")


def summarize(mode, rows):
    watts = [row["watts"] for row in rows]
    mean = statistics.mean(watts)
    sd = statistics.stdev(watts) if len(watts) > 1 else 0.0
    return {
        "mode": mode,
        "measured_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "samples": len(watts),
        "mean_watts": round(mean, 2),
        "sd_watts": round(sd, 2),
        "min_watts": round(min(watts), 2),
        "max_watts": round(max(watts), 2),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["idle", "load"])
    parser.add_argument("--seconds", type=int, default=60)
    parser.add_argument("--model", default=str(ROOT / "target/calibration/qwen05b-f16.gguf"))
    parser.add_argument("--bench", default=str(ROOT / "target/calibration/llama/llama-bench.exe"))
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--no-write", action="store_true")
    args = parser.parse_args()

    bench = None
    try:
        if args.mode == "load":
            for path in (args.model, args.bench):
                if not Path(path).exists():
                    sys.exit(f"missing {path}; follow examples/calibration/README.md first")
            bench = subprocess.Popen(
                [args.bench, "-m", args.model, "-p", "0", "-n", "512", "-t", str(args.threads),
                 "-r", "1", "--prio", "2"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            time.sleep(10)  # let the benchmark reach its steady state before sampling
        rows = samples(args.seconds)
        require_battery(rows)
        capture = summarize(args.mode, rows)
        print(f"{capture['mode']:<5} {capture['mean_watts']:8.2f} W +- {capture['sd_watts']:.2f} "
              f"over {capture['samples']} samples (min {capture['min_watts']:.2f}, "
              f"max {capture['max_watts']:.2f})")
        if not args.no_write:
            stamp = datetime.now().strftime("%Y-%m-%d")
            dest = HERE / "measurements" / f"battery-power-{args.mode}-{stamp}.json"
            payload = dict(capture, note="whole system, screen on, laptop unplugged",
                           model=Path(args.model).name,
                           bench_seconds=args.seconds,
                           threads=args.threads if args.mode == "load" else 0)
            dest.write_text(json.dumps(payload, indent=2) + "\n")
            print(f"wrote {dest}")
    finally:
        if bench is not None:
            bench.terminate()


if __name__ == "__main__":
    main()
