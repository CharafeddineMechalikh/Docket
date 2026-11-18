#!/usr/bin/env python3
"""Writes the synthetic agentic traces Example 14 replays.

The scenario in this folder is an agentic LLM workload over a tiered fleet. Two classes
share the stream, with the deadlines of the P2 study:

    rag-inference      soft real time, 0.80 s end to end
    control-reasoning  hard real time, 0.25 s end to end

P2 states its workload in million instructions and kilobytes and scales them by a compute
multiplier and a payload multiplier. A serving stack has no instruction count: it has a
prompt to prefill and a reasoning trace to decode. The generator therefore writes the
token analogue of the same two equations,

    input  = round(base_input  * payload)  + messages * bytes_of_a_tool_result
    output = round(base_output * compute)  + messages * tokens_of_a_tool_call

with one trace per overhead level, all four drawn from one seed so that every level replays
the same requests at the same instants and only the work per request changes. The arrival
process is a Poisson stream at a fixed mean, the same at every level: the experiment is
about the work each request carries, not about how many of them arrive.

Every figure is SYNTHETIC. Nothing here was recorded from a deployment, and the lengths are
drawn from lognormal distributions whose parameters were chosen to sit in the range an
agentic retrieval call occupies, not measured. The traces are committed so that a replay is
reproducible without running this script; rerunning it with the same seed reproduces the
same bytes.

Usage:  python make_agentic_trace.py [output-directory]
"""
from __future__ import annotations

import math
import os
import random
import sys

DURATION_SECONDS = 300.0
ARRIVAL_RATE_PER_SECOND = 1.6
RAG_SHARE = 0.6

# Base shape of each class, before any overhead is applied: the prompt an agent reads and
# the reasoning trace it emits. The rag class carries the retrieved context, so its prompt
# is long; the control class is short but hard-deadlined.
CLASSES = (
    dict(name="rag-inference", rate=RAG_SHARE, priority=0, e2e=0.80, ttft=0.30,
         input_mean=1500.0, input_sigma=620.0, input_min=384, input_max=6000,
         output_mean=460.0, output_sigma=230.0, output_min=64, output_max=1600),
    dict(name="control-reasoning", rate=1.0 - RAG_SHARE, priority=5, e2e=0.25, ttft=0.10,
         input_mean=380.0, input_sigma=150.0, input_min=96, input_max=1400,
         output_mean=190.0, output_sigma=90.0, output_min=32, output_max=700),
)

# The additive control-message term: an agentic decision round-trips through a planner and
# the tool results come back appended to the transcript, so both the prompt and the answer
# grow by a fixed amount per message whatever the task size.
CONTROL_MESSAGES = 3
CONTROL_PROMPT_TOKENS = 96
CONTROL_ANSWER_TOKENS = 24

OVERHEAD_LEVELS = (("1.0", 1.0), ("1.5", 1.5), ("2.0", 2.0), ("3.0", 3.0))

HEADER = ("arrival_time_s,model_id,input_tokens,output_tokens,priority,service_class,"
          "session_id,ttft_target_s,e2e_target_s")

SEED = 20261116
MODEL_ID = "agentic-1b"


def draw(rng: random.Random, mean: float, sigma: float, low: int, high: int) -> int:
    """Draws one lognormal length, clipped into the range the class is defined on.

    The parameters are the mean and the standard deviation of the LENGTH, which is how a
    trace reader states them, not of the log: a sigma larger than the mean makes the normal
    variate large enough for exp() to overflow, so the shape is solved back into the
    log-space parameters that reproduce the requested mean and spread.
    """
    variance = sigma * sigma
    if variance == 0:
        return int(max(low, min(high, round(mean))))
    mu = math.log(mean * mean / math.sqrt(variance + mean * mean))
    s = math.sqrt(math.log(1.0 + variance / (mean * mean)))
    value = rng.lognormvariate(mu, s)
    return int(max(low, min(high, round(value))))


def build_rows(seed: int) -> list[tuple[float, dict]]:
    """Draws the arrival instants and the base shape of every request, once."""
    rng = random.Random(seed)
    rows = []
    clock = 0.0
    while True:
        clock += rng.expovariate(ARRIVAL_RATE_PER_SECOND)
        if clock >= DURATION_SECONDS:
            break
        pick = rng.random()
        accrued = 0.0
        klass = CLASSES[-1]
        for candidate in CLASSES:
            accrued += candidate["rate"]
            if pick <= accrued:
                klass = candidate
                break
        rows.append((round(clock, 3), klass, draw(rng, klass["input_mean"],
                                                  klass["input_sigma"], klass["input_min"],
                                                  klass["input_max"]),
                     draw(rng, klass["output_mean"], klass["output_sigma"],
                          klass["output_min"], klass["output_max"])))
    return rows


def write_trace(path: str, rows: list, overhead: float) -> int:
    """Writes one overhead level of the trace and returns the number of rows.

    The header is the first line and every later line is data, which is what
    {@code TraceLoader} reads: a comment line would be taken for the header. The provenance
    of these rows - synthetic, drawn at a fixed seed, inflated by the level in the file name
    - is stated in this folder's README and in the scenario that replays them.
    """
    lines = [HEADER]
    for arrival, klass, base_input, base_output in rows:
        prompt = round(base_input * overhead) + CONTROL_MESSAGES * CONTROL_PROMPT_TOKENS
        answer = round(base_output * overhead) + CONTROL_MESSAGES * CONTROL_ANSWER_TOKENS
        lines.append("%g,%s,%d,%d,%d,%s,,%g,%g" % (
            arrival, MODEL_ID, prompt, answer, klass["priority"], klass["name"],
            klass["ttft"], klass["e2e"]))
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines) + "\n")
    return len(rows)


def main() -> None:
    # The optional argument is the output directory documented in the usage line; without it the
    # traces are written to the committed data/ folder next to this script.
    if len(sys.argv) > 1:
        out = os.path.abspath(sys.argv[1])
    else:
        out = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "data"))
    os.makedirs(out, exist_ok=True)
    rows = build_rows(SEED)
    for label, overhead in OVERHEAD_LEVELS:
        path = os.path.join(out, "agentic-overhead-%s.csv" % label)
        written = write_trace(path, rows, overhead)
        print("wrote %-28s %4d rows  overhead %sx" % (os.path.basename(path), written, label))


if __name__ == "__main__":
    main()
