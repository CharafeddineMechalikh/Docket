"""Build the candidate figures for the paper from the artifacts this repository commits.

Every number plotted here is read from a committed measurement or from a run this project wrote.
Nothing is typed into the script except the model dimensions, which are the same constants
``examples/calibration/fit.py`` reads from the GGUF header of the benchmarked file. The two device
coefficients are read from the scenario the engine runs, so a plotted bar is the duration the
simulator itself returns, and re-fitted here from the llama-bench captures as a check that the
scenario still says what the measurements say.

Usage:
    python scripts/plot_figures.py [output_dir]

The default output directory is ``target/figures-new``. Each figure is written as both PDF and PNG.
"""

import csv
import glob
import json
import os
import statistics as stats
import sys

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MEASURED = os.path.join(ROOT, "examples", "calibration", "measurements")
CALIBRATION_SCENARIO = os.path.join(ROOT, "examples", "calibration", "scenarios",
                                    "laptop-cpu-qwen05b.properties")
REPLAYS = os.path.join(ROOT, "target", "replays")
SWEEP = os.path.join(ROOT, "target", "sweep")
TRACES = os.path.join(ROOT, "examples", "real-traces", "data")
PRESSURE = os.path.join(ROOT, "target", "verify-manuscript", "loaded-7b-memory-pressure", "rep01")

# Qwen2.5-0.5B-Instruct f16, from the GGUF header and the publisher config, as in fit.py.
PARAMETERS = 494_032_768
LAYERS = 24
KV_HEADS = 2
HEAD_DIMENSION = 64
KV_DIMENSION = KV_HEADS * HEAD_DIMENSION
WEIGHT_BYTES = PARAMETERS * 2
KV_BYTES_PER_TOKEN = 2 * LAYERS * KV_HEADS * HEAD_DIMENSION * 2

TRACE_ORDER = ["azure-conv", "qwen-traceA", "kimi-conversation", "burstgpt"]
TRACE_STYLES = {"azure-conv": ("0.0", "-"), "qwen-traceA": ("0.4", "--"),
                "kimi-conversation": ("0.65", "-."), "burstgpt": ("0.2", ":")}
WINDOW_SECONDS = {"azure-conv": 424.259, "qwen-traceA": 587.966,
                  "kimi-conversation": 531.0, "burstgpt": 191.0}
SWEEP_FAMILIES = {"rate": ("o", "0.15"), "batch": ("s", "0.45"), "block": ("^", "0.65"),
                  "chunk": ("v", "0.3"), "baseline": ("D", "0.0"), "nopreempt": ("P", "0.55")}


def prefill_flops(tokens, cached=0):
    projections = 2 * PARAMETERS * tokens
    attention = 4 * LAYERS * KV_DIMENSION * (tokens * cached + tokens * (tokens + 1) / 2)
    return projections + attention


def prefill_bytes(tokens, cached=0):
    return WEIGHT_BYTES + KV_BYTES_PER_TOKEN * (cached + tokens)


def decode_flops(context_tokens, sequences=1):
    return 2 * PARAMETERS * sequences + 4 * LAYERS * KV_DIMENSION * context_tokens * sequences


def decode_bytes(context_tokens, sequences=1):
    return WEIGHT_BYTES + KV_BYTES_PER_TOKEN * context_tokens + KV_BYTES_PER_TOKEN * sequences


def roofline_seconds(flops, bytes_, fit):
    return max(flops / fit["achieved"], bytes_ / fit["bandwidth"])


def read_rows(path):
    with open(path, newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def read_json(name):
    with open(os.path.join(MEASURED, name), encoding="utf-8") as handle:
        return json.load(handle)


def declared_coefficients():
    """Read the two device coefficients the way the engine does, from the scenario file."""
    values = {}
    with open(CALIBRATION_SCENARIO, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip()
    return {"achieved": float(values["device.achievedTFLOPS.fp16"]) * 1e12,
            "bandwidth": float(values["device.memoryBandwidthGBs"]) * 1e9}


def fit():
    """Return the coefficients the figures use, plus the rows they were fitted from.

    The values the figures plot are the ones the scenario declares, because those are the ones the
    simulator prices a step with. The same two figures are re-fitted here from the committed
    captures, independently, so the printed comparison shows that the scenario still rounds the
    measurement rather than a number chosen for the plot.
    """
    sweep = read_json("llama-bench-laptop-cpu-2026-10-30.json")
    prefill = [row for row in sweep if row["n_prompt"]]
    batch_one = [row for row in sweep if row["n_gen"]][0]
    mean_context = (batch_one["n_gen"] - 1) / 2
    total_flops = sum(prefill_flops(row["n_prompt"]) for row in prefill)
    total_seconds = sum(row["n_prompt"] / row["avg_ts"] for row in prefill)
    data = {"achieved": total_flops / total_seconds,
            "bandwidth": decode_bytes(mean_context) * batch_one["avg_ts"],
            "prefill": prefill,
            "batch_one": batch_one,
            "depth": read_json("llama-bench-depth-laptop-cpu-2026-10-30.json"),
            "mean_context": mean_context}
    declared = declared_coefficients()
    for key in ("achieved", "bandwidth"):
        relative = 100.0 * (declared[key] / data[key] - 1.0)
        if abs(relative) > 0.05:
            raise SystemExit("The scenario declares %s = %.6e but the captures fit %.6e (%+.2f%%); "
                             "the figure would not describe the measurement" % (key, declared[key],
                                                                                 data[key], relative))
        data["fitted_" + key] = data[key]
        data[key] = declared[key]
    return data


def measured_points(fit_data):
    """Return (flops, bytes, label, kind, measured seconds) for the five benchmarked steps.

    The labels name both rows by the mean context the benchmark swept through, because that is the
    context the simulator prices them at.
    """
    points = [(prefill_flops(row["n_prompt"]), prefill_bytes(row["n_prompt"]),
               "prefill %d" % row["n_prompt"], "prefill", row["n_prompt"] / row["avg_ts"])
              for row in fit_data["prefill"]]
    points.append((decode_flops(fit_data["mean_context"]), decode_bytes(fit_data["mean_context"]),
                   "decode ctx %d" % int(fit_data["mean_context"]), "decode",
                   1 / fit_data["batch_one"]["avg_ts"]))
    for row in fit_data["depth"]:
        if row["n_depth"] != 512:
            continue
        context = row["n_depth"] + (row["n_gen"] - 1) / 2
        points.append((decode_flops(context), decode_bytes(context),
                       "decode ctx %d" % int(context), "decode", 1 / row["avg_ts"]))
    return points


def operating_points(fit_data):
    """Return (flops, bytes, label, kind) for the steps plotted on the roofline.

    Prefill and batch-1 decode come from the benchmark. The larger batches are the same model
    priced at the batch sizes the sweeps explore, which turns the roofline into a statement about
    batching instead of a scatter of five points that pile up on top of each other: at batch 1 the
    weights dominate both terms, so every batch-1 context lands at an intensity of one.
    """
    points = [(flops, bytes_, name, "prefill")
              for flops, bytes_, name, kind, _ in measured_points(fit_data) if kind == "prefill"]
    context = 512 + fit_data["mean_context"]
    for batch in (1, 4, 16):
        points.append((decode_flops(context, batch), decode_bytes(context, batch),
                       "batch %d" % batch, "decode"))
    return points


def signed_percent(value):
    """Format a difference so that a rounding artefact never reads as a negative zero."""
    if abs(value) < 0.05:
        return "0.0%"
    return "%+.1f%%" % value


def style(axes, xlabel, ylabel, log=False, logx=False):
    axes.set_xlabel(xlabel)
    axes.set_ylabel(ylabel)
    if log:
        axes.set_xscale("log")
        axes.set_yscale("log")
    if logx:
        axes.set_xscale("log")
    axes.grid(True, which="major" if log or logx else "both", color="0.85", linewidth=0.6)
    axes.set_axisbelow(True)
    for side in ("top", "right"):
        axes.spines[side].set_visible(False)


def figure_parity(fit_data):
    """Measured against simulated duration, one pair of bars per benchmarked step."""
    points = measured_points(fit_data)
    rows = len(points)
    fig, axes = plt.subplots(figsize=(3.4, 0.46 * rows + 0.8))
    height = 0.34
    for index, (flops, bytes_, name, kind, measured) in enumerate(points):
        simulated = roofline_seconds(flops, bytes_, fit_data)
        y = rows - index
        axes.barh(y + height / 2, measured, height=height, color="0.15", zorder=2)
        axes.barh(y - height / 2, simulated, height=height, color="0.6", zorder=2)
        axes.annotate("%.4f s measured" % measured, xy=(measured * 1.09, y + height / 2),
                      fontsize=6.6, va="center", color="0.15")
        axes.annotate("%.4f s simulated, %s" % (simulated, signed_percent(100.0 * (simulated / measured - 1.0))),
                      xy=(simulated * 1.09, y - height / 2), fontsize=6.6, va="center", color="0.35")
        axes.annotate(name, xy=(-0.02, y), xycoords=("axes fraction", "data"),
                      fontsize=7.4, va="center", ha="right")
    axes.set_xscale("log")
    axes.set_xlim(0.06, 40)
    axes.set_ylim(0.4, rows + 0.7)
    axes.set_yticks([])
    style(axes, "seconds per step", "", logx=True)
    fig.tight_layout()
    return fig


def figure_roofline(fit_data):
    """The fitted roofline with the prefill points it was fitted to and a decode batch ladder."""
    achieved, bandwidth = fit_data["achieved"], fit_data["bandwidth"]
    ridge = achieved / bandwidth
    fig, axes = plt.subplots(figsize=(3.4, 2.9))
    intensity = np.logspace(np.log10(0.7), np.log10(ridge), 200)
    axes.plot(intensity, intensity * bandwidth, color="0.4", linewidth=1.4, zorder=2)
    axes.plot([ridge, 3000], [achieved, achieved], color="0.4", linewidth=1.4, zorder=2)
    axes.axvline(ridge, color="0.7", linewidth=0.8, linestyle=":", zorder=1)
    offsets = {"prefill 256": (1.18, "center"), "prefill 512": (2.4, "center"),
               "prefill 1024": (0.42, "center"), "batch 1": (0.42, "center"),
               "batch 4": (0.42, "center"), "batch 16": (0.42, "center")}
    for flops, bytes_, name, kind in operating_points(fit_data):
        x = flops / bytes_
        y = flops / roofline_seconds(flops, bytes_, fit_data)
        axes.plot(x, y, "o" if kind == "prefill" else "s",
                  color="0.15" if kind == "prefill" else "0.5", markersize=5, zorder=3)
        factor, alignment = offsets[name]
        axes.annotate(name, xy=(x, y), xytext=(x, y * factor), fontsize=6.8, ha=alignment,
                      va="bottom" if factor > 1 else "top",
                      arrowprops=dict(arrowstyle="-", color="0.6", linewidth=0.6), zorder=4)
    style(axes, "arithmetic intensity (FLOP per byte)", "attained FLOP per second", log=True)
    axes.set_xlim(0.75, 3000)
    axes.set_ylim(6e8, achieved * 4.2)
    axes.annotate("compute ceiling %.4f TFLOPS" % (achieved / 1e12), xy=(0.02, 0.955),
                  xycoords="axes fraction", fontsize=6.8, color="0.15")
    axes.annotate("memory ceiling %.3f GB/s" % (bandwidth / 1e9), xy=(0.04, 0.10),
                  xycoords="axes fraction", fontsize=6.8, color="0.15", rotation=26)
    axes.annotate("ridge %.1f" % ridge, xy=(0.31, 0.05), xycoords="axes fraction",
                  fontsize=6.8, color="0.45")
    fig.tight_layout()
    return fig


def cdf(values):
    ordered = np.sort(np.asarray(values, dtype=float))
    return ordered, np.arange(1, ordered.size + 1) / ordered.size


def figure_trace_cdf():
    fig, (left, right) = plt.subplots(1, 2, figsize=(6.6, 2.5))
    panels = (("ttft_s", "time to first token (s)", left), ("e2e_s", "end-to-end latency (s)", right))
    for metric, xlabel, axes in panels:
        for trace in TRACE_ORDER:
            path = os.path.join(REPLAYS, trace, "rep01", "requests.csv")
            values = [float(row[metric]) for row in read_rows(path) if row[metric]]
            x, y = cdf(values)
            color, linestyle = TRACE_STYLES[trace]
            axes.plot(x, y, color=color, linestyle=linestyle, linewidth=1.3, label=trace)
        style(axes, xlabel, "cumulative fraction", logx=True)
        axes.set_ylim(0, 1.04)
        axes.set_yticks([0.25, 0.5, 0.75, 0.99])
        axes.set_yticklabels(["0.25", "0.50", "0.75", "0.99"])
        axes.axhline(0.5, color="0.75", linewidth=0.7, linestyle=":")
        axes.axhline(0.99, color="0.75", linewidth=0.7, linestyle=":")
    left.legend(frameon=False, fontsize=6.8, loc="lower right")
    fig.tight_layout()
    return fig


def figure_cache_occupancy():
    fig, axes = plt.subplots(figsize=(3.4, 2.4))
    rows = read_rows(os.path.join(PRESSURE, "samples.csv"))
    time = [float(row["time_s"]) for row in rows]
    used = [int(row["used_blocks"]) for row in rows]
    capacity = int(rows[0]["total_blocks"])
    axes.fill_between(time, 0, used, color="0.8", zorder=1)
    axes.plot(time, used, color="0.15", linewidth=1.1, zorder=2)
    axes.axhline(capacity, color="0.5", linewidth=1.0, linestyle="--", zorder=3)
    axes.annotate("capacity %d blocks" % capacity, xy=(time[0], capacity),
                  xytext=(time[0] + 0.35, capacity * 1.01), fontsize=7.0, color="0.35",
                  ha="left", va="bottom")
    peak = max(used)
    axes.annotate("peak %d, %.1f%% of capacity" % (peak, 100.0 * peak / capacity),
                  xy=(time[used.index(peak)], peak), xytext=(time[used.index(peak)] + 1.2, peak - 520),
                  fontsize=7.0, arrowprops=dict(arrowstyle="-", color="0.4", linewidth=0.7))
    style(axes, "simulated time (s)", "KV cache blocks in use")
    axes.set_xlim(0, time[-1])
    axes.set_ylim(0, capacity * 1.07)
    fig.tight_layout()
    return fig


def sweep_series():
    series = {}
    for path in glob.glob(os.path.join(SWEEP, "**", "summary.csv"), recursive=True):
        name = os.path.basename(os.path.dirname(os.path.dirname(path)))
        rows = read_rows(path)
        series[name] = {column: stats.fmean(float(row[column]) for row in rows) for column in rows[0]
                        if column not in ("experiment", "outcomes", "cost_model",
                                          "cost_calibration", "repetition", "seed")}
    return series


def parse_outcomes(text):
    """Split an ``outcomes`` cell such as ``completed:success=35; timed_out:x=9`` into a dict.

    The cell keys each outcome by terminal state and reason joined with a colon. The reason alone
    identifies the segment, so the state prefix is dropped here.
    """
    counts = {}
    for part in text.split(";"):
        part = part.strip()
        if not part or "=" not in part:
            continue
        reason, count = part.rsplit("=", 1)
        reason = reason.strip().rsplit(":", 1)[-1]
        counts[reason] = counts.get(reason, 0.0) + float(count)
    return counts


OUTCOME_GROUPS = (("completed", "success", "0.15"),
                  ("rejected", "admission_no_cache_capacity", "0.55"),
                  ("timed_out", "simulation_ended", "0.82"))
OUTCOME_LABELS = {"success": "completed",
                  "admission_no_cache_capacity": "refused: no cache capacity",
                  "simulation_ended": "timed out: run ended"}


def sweep_outcomes():
    """Mean outcome counts per configuration, summed over the repetitions of that configuration."""
    per_config = {}
    for path in glob.glob(os.path.join(SWEEP, "**", "summary.csv"), recursive=True):
        name = os.path.basename(os.path.dirname(os.path.dirname(path)))
        rows = read_rows(path)
        totals = {}
        for row in rows:
            for reason, count in parse_outcomes(row["outcomes"]).items():
                totals[reason] = totals.get(reason, 0.0) + count
        per_config[name] = {reason: totals.get(reason, 0.0) / len(rows) for _, reason, _ in OUTCOME_GROUPS}
    order = ["rate%d" % r for r in (15, 30, 60, 120, 240, 480)] + \
            ["batch%d" % b for b in (4, 8, 16, 32, 64)] + \
            ["block8", "block16", "block32"] + \
            ["chunk0", "chunk128", "chunk512", "chunk2048"] + \
            ["nopreempt", "loaded", "light"]
    return [(name, per_config[name]) for name in order if name in per_config]


def figure_sweep_outcomes():
    rows = sweep_outcomes()
    fig, axes = plt.subplots(figsize=(3.4, 0.235 * len(rows) + 0.9))
    height = 0.62
    for index, (name, counts) in enumerate(rows):
        y = len(rows) - index
        total = sum(counts.values())
        left = 0.0
        for _, reason, shade in OUTCOME_GROUPS:
            value = counts[reason]
            if value <= 0:
                continue
            axes.barh(y, value, height=height, left=left, color=shade, zorder=2)
            left += value
        completed = counts["success"]
        if completed >= 15:
            axes.annotate("%.1f" % completed, xy=(completed / 2, y), fontsize=5.8,
                          ha="center", va="center", color="0.95", zorder=3)
        axes.annotate("%.1f offered" % total, xy=(total * 1.02, y), fontsize=5.8,
                      va="center", ha="left", color="0.2")
        axes.annotate(name, xy=(-0.012, y), xycoords=("axes fraction", "data"),
                      fontsize=6.6, va="center", ha="right")
    axes.set_xlim(0, max(sum(c.values()) for _, c in rows) * 1.24)
    axes.set_ylim(0.4, len(rows) + 0.75)
    axes.set_yticks([])
    style(axes, "requests offered, mean per repetition", "")
    axes.legend(handles=[plt.Rectangle((0, 0), 1, 1, color=shade, label=OUTCOME_LABELS[reason])
                         for _, reason, shade in OUTCOME_GROUPS],
                frameon=False, fontsize=6.4, loc="upper right", ncol=1,
                bbox_to_anchor=(1.0, 0.995))
    fig.tight_layout()
    return fig


def figure_sweep_tradeoff():
    series = sweep_series()
    fig, axes = plt.subplots(figsize=(4.6, 3.0))
    ordered = sorted(series.items(), key=lambda item: item[1]["tokens_per_second"])
    for index, (name, data) in enumerate(ordered):
        family = next((f for f in SWEEP_FAMILIES if name.startswith(f)), "baseline")
        marker, color = SWEEP_FAMILIES[family]
        x, y = data["tokens_per_second"], 100.0 * data["goodput"]
        axes.plot(x, y, marker, color=color, markersize=5)
        above = index % 2 == 0
        axes.annotate(name, xy=(x * 1.02, y + (2.2 if above else -2.2)), fontsize=6.6, color=color,
                      ha="left", va="bottom" if above else "top")
    style(axes, "token throughput (tokens/s)", "goodput (%)")
    axes.set_xlim(0, max(d["tokens_per_second"] for d in series.values()) * 1.26)
    axes.set_ylim(-7, 112)
    axes.legend(handles=[plt.Line2D([], [], marker=SWEEP_FAMILIES[f][0], color=SWEEP_FAMILIES[f][1],
                                    linewidth=0, markersize=5, label=f) for f in SWEEP_FAMILIES],
                frameon=False, fontsize=6.8, loc="lower right", ncol=3)
    fig.tight_layout()
    return fig


def figure_trace_shape():
    fig, axes = plt.subplots(2, 2, figsize=(5.2, 3.5))
    for axis, trace in zip(axes.ravel(), TRACE_ORDER):
        rows = read_rows(os.path.join(TRACES, trace + ".csv"))
        axis.plot([float(row["arrival_time_s"]) for row in rows],
                  [int(row["input_tokens"]) for row in rows], ".", color="0.3", markersize=1.6)
        style(axis, "arrival time (s)", "prompt tokens")
        axis.set_xlim(0, WINDOW_SECONDS[trace])
        axis.set_title("%s, %d rows over %.0f s" % (trace, len(rows), WINDOW_SECONDS[trace]), fontsize=7.6)
    fig.tight_layout()
    return fig


def save(fig, directory, name):
    os.makedirs(directory, exist_ok=True)
    for extension in ("pdf", "png"):
        path = os.path.join(directory, name + "." + extension)
        fig.savefig(path, dpi=200, bbox_inches="tight")
        print("wrote", path)
    plt.close(fig)


def main():
    fit_data = fit()
    print("achieved throughput : %.4e FLOP/s declared, %.4e FLOP/s fitted"
          % (fit_data["achieved"], fit_data["fitted_achieved"]))
    print("bandwidth           : %.4e B/s declared, %.4e B/s fitted"
          % (fit_data["bandwidth"], fit_data["fitted_bandwidth"]))
    for flops, bytes_, name, kind, measured in measured_points(fit_data):
        simulated = roofline_seconds(flops, bytes_, fit_data)
        print("%-16s measured %.4f s, simulated %.4f s, %s"
              % (name, measured, simulated, signed_percent(100.0 * (simulated / measured - 1.0))))
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "target", "figures-new")
    save(figure_parity(fit_data), out, "fig_new_parity")
    save(figure_roofline(fit_data), out, "fig_new_roofline")
    save(figure_trace_cdf(), out, "fig_new_trace_cdf")
    save(figure_cache_occupancy(), out, "fig_new_cache_occupancy")
    save(figure_sweep_outcomes(), out, "fig_new_sweep_outcomes")
    save(figure_sweep_tradeoff(), out, "fig_new_sweep_tradeoff")
    save(figure_trace_shape(), out, "fig_new_trace_shape")


if __name__ == "__main__":
    main()
