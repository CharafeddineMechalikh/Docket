---
description: "How Docket compares to the published LLM inference serving simulators (Vidur, ReaLLM, LLMServingSim, LLMServingSim 2.0, CloudSimLLM) on the mechanisms each models, and when another tool is the better fit."
---
# Comparison

Several discrete-event and analytical simulators now exist for LLM inference serving. This page sets
them against Docket on the mechanisms each one models, so you can pick the right tool for a question
rather than assume one size fits all. Every claim below is taken from the cited paper; where a paper
does not state whether it models something, the cell reads **n/s** (not stated) rather than claiming
it does not.

## Mechanisms at a glance

Legend: **Engine** is the simulation substrate the tool is built on; **Timing** is how a step's
latency is produced; **Bat** continuous batching; **KV** an explicit key-value cache; **Chunk**
chunked prefill; **Pwr** power and energy accounting; **Dis** prefill/decode disaggregation (separate
phases, requests, or replicas in one deployment); **Het** a heterogeneous device fleet; **Edge** an
edge, fog, or mist topology with an access link; **Prov** a provenance label on every reported output.

| Simulator | Engine | Timing | Bat | KV | Chunk | Pwr | Dis | Het | Edge | Prov |
| --- | --- | --- | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: |
| Vidur (2024) | custom | profiled + learned | yes | yes | yes | no | no | no | no | no |
| ReaLLM (2025) | custom | trace-driven | yes | n/s | n/s | no | no | no | no | no |
| LLMServingSim (2024) | custom | cycle-level HW/SW | yes | yes | n/s | no | no | no | no | no |
| LLMServingSim 2.0 (2026) | ASTRA-sim | cycle-level HW/SW | yes | yes | yes | yes | yes | yes | no | no |
| CloudSimLLM (2026) | CloudSim | n/s | n/s | n/s | n/s | yes | n/s | n/s | no | n/s |
| **Docket** | PureEdgeSim | analytical roofline | yes | yes | yes | yes | yes | yes | yes | yes |

## What each tool is good at

- **Vidur** profiles real operators and adds learned models, then searches scheduling and parallelism
  choices on a single datacenter deployment. If your question is "which batch/schedule minimizes cost
  for this model on this hardware", and you can afford the profiling, Vidur's fidelity is the reason
  to reach for it.
- **LLMServingSim** and **LLMServingSim 2.0** model the hardware and the network at cycle level. The
  2.0 line, built on ASTRA-sim, adds disaggregation, heterogeneity, and power. When the mechanism you
  are studying lives inside the accelerator interconnect or the collective, cycle-level detail is what
  you need.
- **ReaLLM** drives its simulation from recorded traces, and **CloudSimLLM** extends the CloudSim
  lineage with a power model. Both are worth a look when you want to build on an existing, published
  simulator rather than an edge-computing engine.

## Where Docket fits

Docket is the only one of these that combines four properties in one tool:

1. **A provenance label on every number.** Each estimate is tagged synthetic, literature, or measured,
   so a reader can never mistake a hand-chosen coefficient for a measurement. The other simulators
   report figures without saying how each was obtained.
2. **A GPU-free, deterministic run.** The roofline cost model is an analytical function of the batch
   and the cache, so the same scenario and seed reproduce the same CSV byte for byte on a laptop, in
   seconds, with no accelerator and no learned model to retrain.
3. **The edge and the link.** Docket inherits the PureEdgeSim topology, so a request can cross an
   access bearer whose store-and-forward queue is charged on the prompt and the response, and
   heterogeneous device fleets (a phone and a datacenter card answering the same model) are first-class.
4. **Disaggregation and heterogeneity together.** Prefill/decode disaggregation onto separate, possibly
   unlike pools, with the KV hand-off queued on a fabric, is measured against colocated serving in
   [Example 13](../examples/13-prefill-decode-disaggregation/README.md).

The trade-off is fidelity per mechanism. Docket prices a step with an analytical roofline, not a
cycle-level model or a learned operator profile, so it will not resolve microarchitectural effects
that Vidur or LLMServingSim 2.0 can. It is built to answer serving-policy and system-design questions
reproducibly and cheaply, and to say honestly which of its numbers are measured and which are assumed.

## How to read the table

The rows are ordered by year of publication. A **no** means the cited paper does not present that
mechanism; an **n/s** means the paper does not state whether it does. If you are relying on a cell for
a decision, read the source: Vidur, ReaLLM, LLMServingSim, LLMServingSim 2.0, and CloudSimLLM are each
published with their own evaluation. Docket's own claims are reproducible from this repository, as
described in [Reproducing results](reproducing.md).
