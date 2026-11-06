---
description: "The Docket code map: every package under com.mechalikh.docket, the classes it holds, and the responsibility of each, so you know where to make a change or which class to call."
---
# API reference

Docket is a thin layer over the PureEdgeSim discrete-event engine: the engine supplies the event
queue, the clock, the entity registry and the seeded randomness, and Docket supplies the domain
objects, the serving entity, the analytical models, the policies and the reporting. This page maps
that layer. It lists each package and the responsibility of every class in it; method-level detail
lives on [Javadoc](https://javadoc.io/doc/io.github.charafeddinemechalikh/docket).

All code is under `com.mechalikh.docket`.

## `cli`

| Class | Responsibility |
| --- | --- |
| `Main` | The command-line entry point. Parses the flags, loads one scenario file, runs the experiment, writes the artifacts, and returns a stable exit code (`0` ok, `2` usage, `3` scenario, `4` run). |

## `config`

| Class | Responsibility |
| --- | --- |
| `Scenario` | Parses and validates one scenario properties file. The loader is strict: a key that is set but never read is reported, and a missing or malformed value is an error rather than a default. |
| `Settings` | Typed access to scenario properties (models, devices, deployment, replica limits, policies, workload, targets) with their validation rules and units. |
| `TraceLoader` | Reads a recorded trace file into replayable rows, applying the time scaling named in the scenario. |

## `domain`

| Class | Responsibility |
| --- | --- |
| `InferenceRequest` | A single serving request. Extends the engine's `AbstractQueueElement`, so it carries the queue ordering, and holds its state, timings, prompt/response sizes and provenance. |
| `ModelProfile` | The model being served: parameter count, context limit, per-token KV bytes, and the terms the roofline reads. |
| `GpuProfile` | A device: compute throughput, memory bandwidth, memory capacity, and the power levels. |
| `RequestState` | The request state machine (`ARRIVED`, `ADMITTED`, `QUEUED`, `PREFILLING`, `DECODING`, `TRANSFERRING`, `PREEMPTED`, `COMPLETED`, `REJECTED`, `CANCELLED`, `TIMED_OUT`) and its legal transitions. |
| `OutcomeReason` | Why a request ended: completed, rejected by admission, timed out, cancelled, or stopped at the context limit. |
| `Provenance` | The label on every estimate: `SYNTHETIC`, `LITERATURE`, or `MEASURED`. |
| `TraceRecord` | One row of a replayed trace: arrival instant, prompt length, and generated length. |

## `engine`

| Class | Responsibility |
| --- | --- |
| `InferenceService` | The serving entity. A `SimEntity` subclass that drives the clock-by-clock loop, owns the replicas, and applies the policies. |
| `ReplicaServer` | One replica. Role-aware: a prefill replica prices no decode and a decode replica prices no prefill; a colocated replica does both. Holds the running batch and the KV pool and advances one step at a time. |
| `ReplicaSpec` | A replica's configuration: its device profile, its serving limits, and its phase role. |
| `WorkloadPlan` | How requests arrive: the arrival pattern, the length distributions, or a trace to replay. |
| `WorkloadSource` | Generates or replays the arrival stream feeding the service. |

## `cost`

| Class | Responsibility |
| --- | --- |
| `ExecutionCostModel` | The interface that prices a step given the batch, the cache and the device. |
| `RooflineAnalyticalCostModel` | The analytical roofline: the binding term between compute and memory bandwidth for prefill and decode. |
| `CalibratedRooflineCostModel` | The roofline with measured coefficients, used when the calibration contract holds. |
| `CostEstimate` | A priced step, carrying its latency and its provenance label. |
| `DecodeBatch` | The set of sequences decoding together in a step and the cost of that step. |

## `memory`

| Class | Responsibility |
| --- | --- |
| `GpuMemoryManager` | The device memory budget: reserves and releases KV blocks and decides when capacity refuses a request. |
| `KvCachePool` | The paged KV cache: block rounding, allocation per sequence, and the capacity an admission policy checks against. |

## `network`

| Class | Responsibility |
| --- | --- |
| `AccessLink` | One access bearer: a first-in first-out store-and-forward queue in each direction, charged on the prompt and on the response and reported as its own columns. |
| `LinkProfile` | The link's capacity and latency profile. Extends the engine's `LinkProfile`, extracted in PureEdgeSim 6.1. |

## `policy`

Three decision layers, each an interface with shipped implementations. A policy that lives outside
the engine is loaded by name through the class-loading hook; it needs a public no-argument constructor.

**Admission** decides whether a request enters.

| Class | Responsibility |
| --- | --- |
| `AdmissionPolicy` | Interface; returns an `AdmissionDecision` for a request against the current state. |
| `AdmissionDecision` | The admit/reject outcome and its reason. |
| `UnlimitedAdmission` | Admits everything (the baseline). |
| `QueueLimitAdmission` | Rejects once the queue exceeds a bound. |
| `CacheCapacityAdmission` | Rejects when the KV pool cannot hold the prompt. |
| `PredictedDeadlineAdmission` | Rejects when the predicted completion would miss the deadline. |
| `CompositeAdmission` | Chains several admission rules. |

**Routing** decides which replica takes a request.

| Class | Responsibility |
| --- | --- |
| `RoutingPolicy` | Interface; picks a replica. |
| `ReplicaLoad` | A read-only view of each replica's queue and batch, used by the load-aware routers. |
| `RoundRobinRouting` | Cycles through replicas. |
| `LeastQueuedRouting` | Sends to the replica with the shortest queue. |
| `LeastPredictedWorkRouting` | Sends to the replica with the least predicted work. |

**Scheduling** decides the order within a queue.

| Class | Responsibility |
| --- | --- |
| `SchedulingPolicy` | Interface; orders the next batch. |
| `FirstComeFirstServed` | Arrival order (the baseline). |
| `PriorityFirst` | Higher priority first. |
| `ShortestExpectedFirst` | Shortest expected work first. |
| `ShortestOracleFirst` | Shortest actual work first, an oracle upper bound for comparison. |

## `power`

| Class | Responsibility |
| --- | --- |
| `GpuPowerModel` | A two-level power model integrated over simulated time. Extends the engine's `EnergyModelComputingNode`. |

## `metrics`

| Class | Responsibility |
| --- | --- |
| `LatencyStats` | Aggregates per-request latency into means and percentiles. |
| `RequestRecorder` | Writes `requests.csv`, `requests.jsonl` and `samples.csv`. |
| `ResourceSample` | One device-state sample in the time series. |
| `SloChecks` | Evaluates the per-class service-level targets and the measured goodput. |

## `experiment`

| Class | Responsibility |
| --- | --- |
| `Experiment` | Runs the repetitions, checks the accounting invariant, and collects the results. |
| `RunResult` | One repetition's aggregated metrics. |
| `Report` | Renders the console report from the finished run. |

## The seams into the engine

Docket extends four PureEdgeSim types rather than reimplementing them: its serving entity is a
`SimEntity` subclass, `InferenceRequest` extends `AbstractQueueElement`, `LinkProfile` extends the
engine's bearer-capacity profile, and `GpuPowerModel` extends `EnergyModelComputingNode`. Keeping that
boundary sharp is what makes the tool auditable: the batch loop is one class, the models are pure
functions of their inputs, and the report is a pure function of the finished run. The inheritance
diagram is in [Architecture](architecture.md).
