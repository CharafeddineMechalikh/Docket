---
description: "Answers to the questions that come up most when running Docket: request failures, small batches, missing goodput, provenance labels, strict scenario loading, disaggregation accounting, reproducibility, and custom policies."
---
# FAQ

Short answers to the questions that come up most. Each points to the page that explains the mechanism
in full.

## Why did a request fail?

A request can end for four reasons, recorded in its `outcome` column: rejected by an admission policy,
timed out against the horizon or a deadline, cancelled by the client, or stopped because it reached the
model or cache context limit before generating the requested tokens (`CONTEXT_LIMIT_REACHED`, which is
not a success). The admission and deadline reasons come from the policy layer; see
[Policies](policies.md).

## Why is my batch smaller than `replica.maxBatchSize`?

The batch limit is a ceiling, not a target. A step is smaller when fewer requests are eligible:
arrivals have not filled the queue, the KV pool refuses more sequences at the current lengths, or an
admission rule is rejecting. The batch ceiling and the memory ceiling are independent; see
[Memory and caching](memory.md) and [Cost model](cost-model.md).

## Why does the report say goodput or SLO attainment is not applicable?

Per-class service-level attainment is only computed when the scenario names targets
(`workload.*TargetSeconds`). A run with no targets reports the latency columns but leaves goodput as
not applicable, because there is nothing to measure attainment against. Set the targets in the scenario;
see [Metrics and artifacts](metrics.md).

## Why is a number labelled synthetic?

Every estimate carries a provenance label: synthetic, literature, or measured. A synthetic label means
the figure comes from the model's coefficients, not from hardware. Nothing is presented as calibrated
until the calibration contract holds, so a synthetic label is honest, not a bug. See
[Cost model](cost-model.md).

## Why will my scenario not load, or why do I see an unused-key warning?

The loader is strict. A missing required key, a bad value, or an unreadable trace is an error and the
process exits with code `3`. A key that is set but never read prints a warning but does not change the
exit code, because a silently ignored setting is how a result ends up describing something other than
what you wrote. Check the property name against [Configuration](configuration.md).

## Why does a disaggregated run add no extra column to the CSV?

By design. The key-value hand-off is charged onto the fabric between the prefill step and the first
token, so its delay already sits inside `ttft_s` and `e2e_s`; there is no separate transfer column. A
deployment that names no `deployment.fabric` hands off instantly, and its row is identical to a
colocated one. See [Metrics and artifacts](metrics.md).

## Why are some timestamps empty and others zero?

Empty means the event never happened; a `0` in a timestamp column is a real instant at the start of the
run, not a sentinel. Do not read `0` as "missing". The full column list is in
[Metrics and artifacts](metrics.md).

## Why are preemptions high?

Preemption is triggered by memory pressure: when the KV pool cannot admit the next sequence, a victim
is evicted and recomputed later. High preemption usually means the batch or the prompt lengths exceed
the device's KV capacity at the configured `tokensPerBlock`. Tune the pool, the batch ceiling, or the
victim order; see [Memory and caching](memory.md).

## Why do two runs give different numbers?

Randomness (arrivals and lengths) is seeded. The same scenario and the same seed reproduce the same
CSV byte for byte. Different seeds give different draws, which is why sweeps use repetitions and a
fixed seed to report a stable figure. See [Reproducing results](reproducing.md).

## Why does my custom policy not load?

A policy outside the engine needs a public no-argument constructor, must be on the runtime classpath,
and must be named in the scenario. If the class is not found or the constructor is missing, the engine
cannot instantiate it. See [Contributing](contributing.md) and [API reference](api-reference.md).

## Why is energy reported when there is no GPU?

Energy comes from a two-level power model integrated over simulated time, not from a measured device.
It is a modelled quantity with a provenance label, so it is comparable across configurations but is not
a statement about real hardware. See [Cost model](cost-model.md).

## Why is a run slow, and how do I speed it up?

Wall-clock time scales with the number of requests, the repetitions, and the trace length. Use
`--quiet` to skip the console report, lower the repetitions while iterating, and keep the fixed seed
only for the runs you intend to publish. The committed sweeps in `scripts/run_sweeps.sh` are the
configurations worth the time.
