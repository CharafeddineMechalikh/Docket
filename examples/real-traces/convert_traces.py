#!/usr/bin/env python3
"""Cut one contiguous window from each public trace and write the simulator's CSV schema.

The upstream formats differ in units (seconds, milliseconds, wall-clock stamps), in column names
and in what they record. This script makes the three transformation decisions explicit:

    arrival_time_s   the recorded instant minus the first row of the window, in seconds
    model_id         one identifier per trace, matching model.id in the paired scenario
    service_class    the recorded request type where the trace has one, "default" otherwise

Every length is kept exactly as recorded. No row is dropped for being long, no length is clamped,
and the only reordering, if any, is a stable sort that the manifest reports. Rows must reach the
simulator in non-decreasing time, so a trace whose file order is not sorted would be reordered
rather than refused; the counts in the manifest make that visible.

Usage:
    python examples/real-traces/fetch_traces.py
    python examples/real-traces/convert_traces.py [--cache DIR] [--out DIR] [name ...]

Run both from the repository root. The output goes to examples/real-traces/data by default and is
committed, so a reviewer can replay the traces without downloading anything.
"""

import argparse
import csv
import hashlib
import json
import statistics
import sys
from datetime import datetime
from pathlib import Path

LICENSE_AZURE = "CC-BY-4.0"
LICENSE_APACHE = "Apache-2.0"

TRACES = {
    "azure-conv": {
        "file": "azure_conv_2023.csv",
        "kind": "azure",
        "model": "azure-conv",
        "url": "https://github.com/Azure/AzurePublicDataset/blob/master/data/"
               "AzureLLMInferenceTrace_conv.csv",
        "license": LICENSE_AZURE,
        "citation": "Azure Public Dataset, Azure LLM inference trace 2023, conversation subset",
        "window": "the first 600 data rows",
        "window_kind": "index",
        "start": 0,
        "rows": 600,
    },
    "qwen-traceA": {
        "file": "qwen_traceA_part.jsonl",
        "kind": "qwen",
        "model": "qwen-traceA",
        "url": "https://github.com/alibaba-edu/qwen-bailian-usagetraces-anon",
        "license": LICENSE_APACHE,
        "citation": "Qwen-Bailian anonymous trace, qwen_traceA (To-C, chat-style)",
        "window": "the first 600 rows",
        "window_kind": "index",
        "start": 0,
        "rows": 600,
    },
    "kimi-conversation": {
        "file": "mooncake_conversation.jsonl",
        "kind": "mooncake",
        "model": "kimi-conversation",
        "url": "https://github.com/kvcache-ai/Mooncake/tree/main/FAST25-release/traces",
        "license": LICENSE_APACHE,
        "citation": "Mooncake FAST'25 conversation trace, the Kimi serving workload",
        "window": "the first 600 rows at or after 2,799,000 ms, the densest ten minutes",
        "window_kind": "time_s",
        "start": 2_799.0,
        "rows": 600,
    },
    "burstgpt": {
        "file": "burstgpt_1_part.csv",
        "kind": "burstgpt",
        "model": "burstgpt",
        "url": "https://github.com/HPMLL/BurstGPT",
        "license": "CC-BY-4.0",
        "citation": "BurstGPT, the workload AI Perf ships as its bursty public dataset",
        "window": "the first 600 rows at or after 824,947 s, the densest burst in the prefix",
        "window_kind": "time_s",
        "start": 824_947,
        "rows": 600,
    },
}

# BurstGPT records two log types; the labels are shortened so that they read as class names.
BURSTGPT_CLASSES = {"Conversation log": "conversation", "API log": "api"}


class Row:
    """One request after parsing, with its recorded instant kept absolute until the window is cut."""

    __slots__ = ("arrival", "input_tokens", "output_tokens", "service_class", "session")

    def __init__(self, arrival, input_tokens, output_tokens, service_class="default", session=""):
        self.arrival = arrival
        self.input_tokens = input_tokens
        self.output_tokens = output_tokens
        self.service_class = service_class
        self.session = session


def parse_azure_stamp(stamp):
    """Turns '2023-11-16 18:15:46.6805900' into seconds, keeping the 100 ns fraction."""
    base, _, fraction = stamp.partition(".")
    instant = datetime.strptime(base, "%Y-%m-%d %H:%M:%S")
    return instant.timestamp() + (float("0." + fraction) if fraction else 0.0)


def read_azure(path, drops):
    rows = []
    with open(path, encoding="utf-8", newline="") as handle:
        for record in csv.DictReader(handle):
            if not record.get("TIMESTAMP") or not record.get("ContextTokens") \
                    or not record.get("GeneratedTokens"):
                drops["missing"] += 1
                continue
            rows.append(Row(parse_azure_stamp(record["TIMESTAMP"]),
                            int(record["ContextTokens"]), int(record["GeneratedTokens"])))
    return rows


def read_jsonl(path, drops):
    rows = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                drops["truncated"] += 1
                break
            rows.append(record)
    return rows


def read_qwen(path, drops):
    records = read_jsonl(path, drops)
    rows = []
    for record in records:
        try:
            rows.append(Row(float(record["timestamp"]), int(record["input_length"]),
                            int(record["output_length"]), record.get("type", "default"),
                            str(record.get("chat_id", ""))))
        except (KeyError, TypeError, ValueError):
            drops["missing"] += 1
    return rows


def read_mooncake(path, drops):
    records = read_jsonl(path, drops)
    rows = []
    for record in records:
        try:
            rows.append(Row(float(record["timestamp"]) / 1000.0, int(record["input_length"]),
                            int(record["output_length"])))
        except (KeyError, TypeError, ValueError):
            drops["missing"] += 1
    return rows


def read_burstgpt(path, drops):
    rows = []
    with open(path, encoding="utf-8", newline="") as handle:
        for record in csv.DictReader(handle):
            if not record.get("Request tokens") or not record.get("Response tokens") \
                    or not record.get("Timestamp"):
                drops["missing"] += 1
                continue
            label = record.get("Log Type") or ""
            rows.append(Row(float(record["Timestamp"]), int(record["Request tokens"]),
                            int(record["Response tokens"]),
                            BURSTGPT_CLASSES.get(label, label.lower().replace(" ", "-") or "default")))
    return rows


READERS = {
    "azure": read_azure,
    "qwen": read_qwen,
    "mooncake": read_mooncake,
    "burstgpt": read_burstgpt,
}


def select(rows, recipe, drops):
    kind = recipe["window_kind"]
    if kind == "index":
        window = rows[recipe["start"]:recipe["start"] + recipe["rows"]]
    else:
        window = [row for row in rows if row.arrival >= recipe["start"]][:recipe["rows"]]
    if len(window) < recipe["rows"]:
        raise SystemExit("window holds %d rows, the recipe asks for %d" % (len(window), recipe["rows"]))
    out_of_order = sum(1 for before, after in zip(window, window[1:]) if after.arrival < before.arrival)
    if out_of_order:
        window.sort(key=lambda row: row.arrival)
        drops["out_of_order"] = out_of_order
    return window


def write_csv(path, model_id, window, origin):
    with open(path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(["arrival_time_s", "model_id", "input_tokens", "output_tokens",
                         "service_class", "session_id"])
        for row in window:
            writer.writerow(["%.3f" % (row.arrival - origin), model_id, row.input_tokens,
                             row.output_tokens, row.service_class, row.session])


def summarise(name, recipe, window, origin, cache_file, drops):
    arrivals = [row.arrival - origin for row in window]
    inputs = [row.input_tokens for row in window]
    outputs = [row.output_tokens for row in window]
    span = arrivals[-1] if arrivals else 0.0
    entry = {
        "trace": name,
        "upstream": recipe["url"],
        "citation": recipe["citation"],
        "license": recipe["license"],
        "cache_file": cache_file.name,
        "cache_sha256": hashlib.sha256(cache_file.read_bytes()).hexdigest(),
        "window": recipe["window"],
        "rows": len(window),
        "first_arrival_s": round(arrivals[0], 3),
        "last_arrival_s": round(arrivals[-1], 3),
        "mean_rate_per_min": round(len(window) / span * 60.0, 2) if span else None,
        "input_tokens": {"min": min(inputs), "mean": round(statistics.mean(inputs), 1),
                         "max": max(inputs)},
        "output_tokens": {"min": min(outputs), "mean": round(statistics.mean(outputs), 1),
                          "max": max(outputs)},
        "service_classes": sorted({row.service_class for row in window}),
        "dropped": drops,
    }
    return entry


def convert(name, cache, out):
    recipe = TRACES[name]
    cache_file = cache / recipe["file"]
    if not cache_file.exists():
        raise SystemExit("missing %s; run fetch_traces.py first" % cache_file)
    drops = {"missing": 0, "truncated": 0, "out_of_order": 0}
    rows = READERS[recipe["kind"]](cache_file, drops)
    window = select(rows, recipe, drops)
    target = out / (name + ".csv")
    write_csv(target, recipe["model"], window, window[0].arrival)
    entry = summarise(name, recipe, window, window[0].arrival, cache_file, drops)
    print("%-18s %3d rows  %.1f-%.1f s  %s" % (name, entry["rows"], entry["first_arrival_s"],
                                               entry["last_arrival_s"], target))
    return entry


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--cache", default="target/traces-cache",
                        help="directory holding the upstream files (default: target/traces-cache)")
    parser.add_argument("--out", default="examples/real-traces/data",
                        help="directory that receives the converted windows")
    parser.add_argument("names", nargs="*", choices=[[]] + list(TRACES),
                        help="traces to convert; all of them when omitted")
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    # A partial run updates its own entries and leaves the other traces' records alone.
    manifest = []
    manifest_path = out / "manifest.json"
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    fresh = [convert(name, Path(args.cache), out) for name in args.names or list(TRACES)]
    replaced = {entry["trace"] for entry in fresh}
    manifest = [entry for entry in manifest if entry["trace"] not in replaced] + fresh
    order = {name: index for index, name in enumerate(TRACES)}
    manifest.sort(key=lambda entry: order.get(entry["trace"], len(order)))
    with open(out / "manifest.json", "w", encoding="utf-8", newline="\n") as handle:
        json.dump(manifest, handle, indent=2)
        handle.write("\n")
    print("wrote %s" % (out / "manifest.json"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
