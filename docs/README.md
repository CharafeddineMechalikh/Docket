---
description: "The Docket manual: getting started, architecture, configuration, cost model, memory, policies, metrics, trace replay, reproduction, comparison, FAQ, contributing and API reference for the LLM inference serving simulator."
permalink: /docs/
---
# Docket documentation

This folder is the long-form manual for Docket, written for researchers, PhD students, and engineers
who need to understand, configure, extend, and trust the simulator. The guides explain both **how to
use** Docket and **why it behaves the way it does**. The [project README](../README.md) is the tour;
these pages are the reference, and each one states the rules the code enforces and the numbers or
commands that check the claim.

If you are looking for the shortest path to a finished run, read [Getting started](getting-started.md).
If you want to understand the internals before changing anything, read [Architecture](architecture.md)
and [Cost model](cost-model.md).

## Reading paths

### Path 0: I am still choosing a simulator

1. [Comparison](comparison.md): the mechanisms Docket models against the published LLM serving
   simulators (Vidur, ReaLLM, LLMServingSim, LLMServingSim 2.0, CloudSimLLM), including the cases
   where another tool is the better fit.

### Path 1: I want to run an experiment this week

1. [Getting started](getting-started.md): install, build, run a scenario, read the output.
2. [Configuration](configuration.md): change the model, the devices, the deployment, the workload,
   the policies, and the service-level targets.
3. [Metrics and artifacts](metrics.md): understand the CSV columns, the console report, and the charts.
4. [Reproducing results](reproducing.md): run the committed sweeps and check a published number.
5. If your workload was measured rather than assumed, replay it: [Trace replay](traces.md) and the
   four committed public windows in [Real trace replays](../examples/real-traces/README.md).
6. If your claim is about prefill/decode disaggregation, measure it against colocated serving:
   [Example 13](../examples/13-prefill-decode-disaggregation/README.md).

### Path 2: I want to implement a new admission, routing, or scheduling policy

1. [Getting started](getting-started.md).
2. [Architecture](architecture.md): where decisions happen in the event flow.
3. [Policies](policies.md): the three policy interfaces, the shipped implementations, the oracle
   modes, and the class-loading hook for a policy that lives outside the engine.
4. [API reference](api-reference.md): the classes and methods you will call.
5. [Worked examples](../examples/README.md): twelve policies, each a buildable template.

### Path 3: I want to change how the simulated system works

1. [Architecture](architecture.md): packages, the serving entity, and the seams into PureEdgeSim.
2. [Cost model](cost-model.md): the roofline formulas, the binding term, and the calibration contract.
3. [Memory and caching](memory.md): the paged KV pool, block rounding, and preemption with recompute.
4. [Contributing](contributing.md): tests, conventions, and the Javadoc build.

## Documentation map

| Document | What it answers |
| --- | --- |
| [Comparison](comparison.md) | How does Docket stack up against the other LLM serving simulators, and when should I pick one of them instead? |
| [Getting started](getting-started.md) | How do I install, build, and run Docket? What does a first run look like? |
| [Architecture](architecture.md) | How is the simulator organized? How does the discrete-event engine drive a request? Why is a run deterministic? |
| [Configuration](configuration.md) | What does every property in a scenario file mean, what is its default, and what are its units? |
| [Cost model](cost-model.md) | How are prefill and decode latency, batching, and energy computed? What is the calibration contract? |
| [Memory and caching](memory.md) | How does device memory become a KV pool, how are blocks reserved, and what does preemption do? |
| [Policies](policies.md) | How do admission, routing, and scheduling decide placement and order, and how do I plug in my own? |
| [Metrics and artifacts](metrics.md) | How do I read the CSV, the JSONL, the console report, and the SLO and provenance columns? |
| [Trace replay](traces.md) | How do I replay a recorded production trace and map it onto the scenario format? |
| [Reproducing results](reproducing.md) | How do I rebuild, re-run the sweeps, and confirm a published number bit for bit? |
| [FAQ](faq.md) | Why did a request fail? Why is the batch small? Why is the run slow? Why is a figure labelled synthetic? |
| [Contributing](contributing.md) | How do I run the tests, keep the accounting invariant, and build the Javadoc? |
| [API reference](api-reference.md) | Which package and class should I use for a given job? |
| [Worked examples](../examples/README.md) | Runnable scenarios that test a policy or a deployment choice, each with its measured effect. |

## The one-paragraph mental model

Docket is a **discrete-event simulator for generative-AI (LLM) inference serving**, built on the
open-source [PureEdgeSim](https://github.com/CharafeddineMechalikh/PureEdgeSim) engine. You describe
a model, a fleet of devices, and a stream of requests in one properties file. The engine generates the
requests, feeds them to a serving entity, and lets three policy layers decide **admission** (does a
request enter), **routing** (which replica takes it), and **scheduling** (what runs in each step).
Serving couples two phases: a compute-bound **prefill** that consumes the whole prompt and fills a
paged **KV cache**, and a memory-bound **decode** that re-reads the cache for every token. Each phase
is priced by a **roofline** cost model, memory is a bounded pool that can refuse, preempt, and
recompute, and every reported figure carries a **provenance** label (synthetic, literature, or
measured) so an estimate can never be mistaken for a measurement. When the run ends, the framework
aggregates per-request latency, batching, cache use, energy, and service-level attainment into a CSV
row, a JSONL record, and a console report.

## Other sources of information

- [Project README](../README.md): features, the tour, provenance and limits, citation.
- [Worked examples](../examples/README.md): thirteen runnable scenarios with increasing complexity.
- [Real trace replays](../examples/real-traces/README.md): the four committed public windows and their measured results.
- [Javadoc](https://javadoc.io/doc/io.github.charafeddinemechalikh/docket): method-level API documentation.
- [Source on GitHub](https://github.com/CharafeddineMechalikh/Docket): the code and the issue tracker.

## Conventions used in this manual

- **Simulation time** is the time inside the simulated world, measured in seconds unless stated otherwise.
- **Wall-clock time** is the real time your computer spends running the simulation.
- **Prefill** and **decode** are the two serving phases; a **token** is one unit of generated output and a
  **block** is the unit of KV-cache allocation.
- **Synthetic**, **literature**, and **measured** are the three provenance labels Docket stamps on every
  estimate; nothing is presented as calibrated until the calibration contract in [Cost model](cost-model.md) holds.
- Configuration examples assume a working directory at the repository root and the default `scenarios/`
  folder. Paths are configurable, see [Configuration](configuration.md).
