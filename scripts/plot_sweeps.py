"""Plot the measured parameter sweeps that accompany the paper.

Reads the ``summary.csv`` files written by the simulator runs under the sweep
root (one directory per configuration, one repetition column per row) and
writes two PDF/PNG figures.  Every plotted number is the arithmetic mean over
the repetitions the sweep was run with; nothing is interpolated or smoothed.

Usage:
    python scripts/plot_sweeps.py [sweep_root] [figure_dir]

The defaults are ``target/sweep`` and ``target/figures``, which is where
``scripts/run_sweeps.sh`` writes.
"""

import csv
import glob
import os
import statistics as stats
import sys

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_ROOT = os.path.join(ROOT, "target", "sweep")
DEFAULT_FIGURES = os.path.join(ROOT, "target", "figures")


def load(root, prefix):
    """Return {{parameter value: {{column: mean}}}} for every configuration of a sweep."""
    series = {}
    for path in sorted(glob.glob(os.path.join(root, prefix + "*", "*", "summary.csv"))):
        name = os.path.basename(os.path.dirname(path))
        if not name.startswith(prefix):
            continue
        value = float(name[len(prefix):])
        with open(path, newline="", encoding="utf-8") as handle:
            rows = list(csv.DictReader(handle))
        series[value] = {
            column: stats.fmean(float(row[column]) for row in rows)
            for column in rows[0]
            if column not in ("experiment", "outcomes", "cost_model", "cost_calibration")
        }
    return dict(sorted(series.items()))


def style(axes, xlabel, ylabel, logx=False):
    axes.set_xlabel(xlabel)
    axes.set_ylabel(ylabel)
    if logx:
        axes.set_xscale("log")
    axes.grid(True, which="both", color="0.85", linewidth=0.6)
    axes.set_axisbelow(True)
    for side in ("top", "right"):
        axes.spines[side].set_visible(False)


def plot_rate(root):
    data = load(root, "rate")
    if not data:
        return None
    rates = list(data)
    fig, (left, right) = plt.subplots(1, 2, figsize=(6.6, 2.7))

    left.plot(rates, [data[r]["requests_offered"] for r in rates], "o-", color="0.15",
              markersize=4, label="offered")
    left.plot(rates, [data[r]["requests_completed"] for r in rates], "s--", color="0.45",
              markersize=4, label="completed")
    left.plot(rates, [100.0 * data[r]["goodput"] for r in rates], "^:", color="0.0",
              markersize=4, label="goodput (%)")
    style(left, "offered load (requests/min)", "requests in 30 s", logx=True)
    left.set_xticks(rates)
    left.set_xticklabels([str(int(r)) for r in rates])
    left.legend(frameon=False, fontsize=8, loc="upper left")

    # TTFT percentiles are reported in milliseconds for readability.
    right.plot(rates, [1e3 * data[r]["ttft_s_mean"] for r in rates], "o-", color="0.15",
               markersize=4, label="mean")
    right.plot(rates, [1e3 * data[r]["ttft_s_p99"] for r in rates], "s--", color="0.45",
               markersize=4, label="p99")
    style(right, "offered load (requests/min)", "time to first token (ms)", logx=True)
    right.set_xticks(rates)
    right.set_xticklabels([str(int(r)) for r in rates])
    right.legend(frameon=False, fontsize=8, loc="upper left")

    fig.tight_layout()
    return fig


def plot_batch(root):
    data = load(root, "batch")
    if not data:
        return None
    ceilings = list(data)
    fig, (left, right) = plt.subplots(1, 2, figsize=(6.6, 2.7))

    left.plot(ceilings, [data[b]["requests_completed"] for b in ceilings], "o-", color="0.15",
              markersize=4, label="completed")
    left.plot(ceilings, [100.0 * data[b]["goodput"] for b in ceilings], "^:", color="0.0",
              markersize=4, label="goodput (%)")
    style(left, "batch ceiling (sequences)", "requests in 30 s")
    left.set_xscale("log", base=2)
    left.set_xticks(ceilings)
    left.set_xticklabels([str(int(b)) for b in ceilings])
    left.legend(frameon=False, fontsize=8, loc="lower right")

    right.plot(ceilings, [data[b]["mean_batch"] for b in ceilings], "o-", color="0.15",
               markersize=4, label="mean batch")
    right.plot(ceilings, [data[b]["peak_batch"] for b in ceilings], "s--", color="0.45",
               markersize=4, label="peak batch")
    right.axhline(19.0, color="0.65", linestyle=":", linewidth=1)
    right.annotate("cache-imposed ceiling (19)", xy=(4.4, 19.0), xytext=(4.6, 15.2),
                   fontsize=8, color="0.35")
    style(right, "batch ceiling (sequences)", "sequences per iteration")
    right.set_xscale("log", base=2)
    right.set_xticks(ceilings)
    right.set_xticklabels([str(int(b)) for b in ceilings])
    right.set_ylim(0, 21)
    right.legend(frameon=False, fontsize=8, loc="lower right")

    fig.tight_layout()
    return fig


def save(fig, directory, name):
    if fig is None:
        print("missing sweep data for", name)
        return
    os.makedirs(directory, exist_ok=True)
    for extension in ("pdf", "png"):
        path = os.path.join(directory, name + "." + extension)
        fig.savefig(path, dpi=200, bbox_inches="tight")
        print("wrote", path)


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_ROOT
    figures = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_FIGURES
    save(plot_rate(root), figures, "fig_sweep_rate")
    save(plot_batch(root), figures, "fig_sweep_batch")


if __name__ == "__main__":
    main()
