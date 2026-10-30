#!/usr/bin/env python3
"""Fit the two device coefficients from the committed llama-bench captures.

The roofline in Docket prices a step as

    seconds = max(flops / achievedFlopsPerSecond, bytes / bandwidthBytesPerSecond)

so two device-wide coefficients are needed. Prefill is compute-bound and decode at batch 1 is
memory-bound, which is what makes one sweep enough:

    achievedFlopsPerSecond = sum(prefill flops) / sum(prefill seconds)
    bandwidthBytesPerSecond = decode bytes per token / measured seconds per token

The model constants below are read from the GGUF metadata of the benchmarked file (llama-bench
reports them as model_n_params and model_size) and cross-checked against the publisher's
config.json. They are the same numbers the scenario file declares.

Usage:
    python examples/calibration/fit.py
"""

import json
from pathlib import Path

HERE = Path(__file__).resolve().parent

# Qwen2.5-0.5B-Instruct f16, from the GGUF header and the publisher config.
PARAMETERS = 494_032_768
LAYERS = 24
KV_HEADS = 2
HEAD_DIMENSION = 64
KV_DIMENSION = KV_HEADS * HEAD_DIMENSION
WEIGHT_BYTES = PARAMETERS * 2
KV_BYTES_PER_TOKEN = 2 * LAYERS * KV_HEADS * HEAD_DIMENSION * 2


def prefill_flops(tokens, cached=0):
    projections = 2 * PARAMETERS * tokens
    attention = 4 * LAYERS * KV_DIMENSION * (tokens * cached + tokens * (tokens + 1) / 2)
    return projections + attention


def decode_bytes(context_tokens, sequences=1):
    # weights + cache read + cache write, with activations left at zero as in the scenario.
    return WEIGHT_BYTES + KV_BYTES_PER_TOKEN * context_tokens + KV_BYTES_PER_TOKEN * sequences


def read(path):
    return json.loads((HERE / "measurements" / path).read_text())


prefill = [row for row in read("llama-bench-laptop-cpu-2026-10-30.json") if row["n_prompt"]]
decode = [row for row in read("llama-bench-laptop-cpu-2026-10-30.json") if row["n_gen"]]

total_flops = sum(prefill_flops(row["n_prompt"]) for row in prefill)
total_seconds = sum(row["n_prompt"] / row["avg_ts"] for row in prefill)
achieved_flops = total_flops / total_seconds

# llama-bench reports the mean over 128 generated tokens at depth 0, so the contexts seen are
# 0..127 and the cache write adds one more token per step.
row = decode[0]
mean_context = (row["n_gen"] - 1) / 2
bytes_per_token = decode_bytes(mean_context)
bandwidth = bytes_per_token / (1 / row["avg_ts"])

print(f"parameters                : {PARAMETERS}")
print(f"weights                   : {WEIGHT_BYTES} B")
print(f"cache per token           : {KV_BYTES_PER_TOKEN} B")
print()
for row in prefill:
    seconds = row["n_prompt"] / row["avg_ts"]
    print(f"prefill {row['n_prompt']:>5} tokens : {row['avg_ts']:7.2f} tok/s, {seconds:8.4f} s")
print(f"prefill seconds total     : {total_seconds:.4f} s")
print(f"prefill flops total       : {total_flops:.6e}")
print()
print(f"achieved FLOPS            : {achieved_flops:.4e}  ({achieved_flops / 1e12:.4f} TFLOPS)")
print()
for row in decode:
    print(f"decode depth {row['n_depth']:>4}      : {row['avg_ts']:7.2f} tok/s")
print(f"decode mean context       : {mean_context:.1f} tokens")
print(f"decode bytes per token    : {bytes_per_token:.6e}")
print()
print(f"effective bandwidth       : {bandwidth:.6e}  ({bandwidth / 1e9:.3f} GB/s)")
print()
print("scenario settings:")
print(f"model.parameterCount={PARAMETERS}")
print(f"device.achievedTFLOPS.fp16={achieved_flops / 1e12:.4f}")
print(f"device.memoryBandwidthGBs={bandwidth / 1e9:.3f}")
