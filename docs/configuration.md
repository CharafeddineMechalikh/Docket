# Configuration

A scenario is a UTF-8 properties file. This page lists every key, its default, its unit and the
rule the loader enforces. The [README](../README.md#configuration-reference) has the short tables;
this page adds the validation details and a fully annotated example.

## How a file is read

- Encoding is UTF-8, not ISO-8859-1, so a model name with a non-ASCII character survives.
- Lines starting with `#` or `!` are comments.
- A key is trimmed; a blank value is treated as absent, not as an empty string.
- There is no interpolation and no include mechanism. A scenario is one file.
- Lists are comma separated, and empty entries are dropped: `a, b,,c` is three entries.

## Precedence and overrides

```text
defaults  <  scenario file  <  -D key=value  <  -s, -r, -o
```

`-D` overrides the file. The dedicated flags (`-s`, `-r`, `-o`) are applied after every `-D`
regardless of their position on the command line, so they always win. An override may introduce a
key the file does not set.

Every key keeps the label of its origin (`scenario file`, `command line` or `derived`), and
`config.txt` echoes key, effective value and origin. A value the loader computes (for example the
trace description) is marked `derived` and cannot collide with a configured key.

## Strictness

Two behaviours catch the mistakes that silently change a result:

1. A required key that is missing, blank or malformed stops the load with a message naming the
   key and the offending value. The process exits with code `3`.
2. A key that no code read is printed as `warning: the scenario sets "<key>" but nothing reads it`.
   The run continues, because the key may be documentation for a human reader, but the warning is
   the signature of a typo.

The loader is strict about consistency, not only syntax:

- the workload's `modelId` must equal the served model, unless the workload is a trace in which
  each row names its model;
- the device must declare an achieved throughput for the model's precision;
- at least one `device.achievedTFLOPS.<precision>` must exist, because the cost model divides by
  an achieved figure and refuses to divide by a published peak;
- `device.unusableMemoryFraction` must lie in `[0, 1)`;
- a time profile with `N` rates needs `N - 1` ascending boundaries;
- a non-trace workload needs a prompt distribution and either an output distribution or a positive
  `outputToInputRatio`;
- an experiment runs at least one repetition, a duration that is positive and finite, a batch of at
  least one sequence, and a block of at least one token.

## Units

| Quantity | Unit and convention |
|---|---|
| Memory | Binary: KiB, MiB, GiB (`device.memoryGiB`, `replica.runtimeOverheadGiB`) |
| Bandwidth | Decimal: GB/s |
| Throughput | Decimal: TFLOPS |
| Time | Seconds, except keys whose name ends in `Ms`, which take milliseconds |
| Power | Watts |
| Energy | Joules in the artifacts, watt-hours alongside |

Mixing binary memory with decimal bandwidth is deliberate: it follows how a runtime reports
capacity and how a datasheet reports bandwidth. Writing one convention for both is how a cache
ends up a factor of 1.07 too large, which is small enough to hide.

## `experiment.*`

| Key | Default | Rule |
|---|---|---|
| `experiment.name` | `run` | Used in the artifacts and as the run directory; keep it filesystem safe |
| `experiment.durationSeconds` | required | Positive and finite; caps the engine clock |
| `experiment.seed` | `1` | Seed of repetition 1; later repetitions derive their own |
| `experiment.repetitions` | `1` | Integer, at least 1 |
| `experiment.outputDir` | `output` | Resolved against the working directory; `-o` wins |
| `metrics.samplingIntervalSeconds` | `1` | Zero or more; zero disables `samples.csv` rows |

Relative input paths (a trace) are resolved against the scenario file; the output directory is
resolved against the working directory. An input belongs to the scenario, an output belongs to the
invocation that produced it.

## `model.*`

| Key | Default | Rule |
|---|---|---|
| `model.id` | required | Non-empty; matched against `workload.modelId` |
| `model.name` | `model.id` | Free text |
| `model.version` | empty | Free text, echoed |
| `model.architecture` | `dense-transformer` | Free text, echoed |
| `model.parameterCount` | required | Number with optional `K`, `M`, `B`, `T` suffix; commas allowed |
| `model.layers` | required | Positive integer |
| `model.hiddenDimension` | required | Positive integer, divisible by the number of heads |
| `model.attentionHeads` | required | Positive integer |
| `model.kvHeads` | `attentionHeads` | Positive; smaller under grouped-query attention |
| `model.headDimension` | required | Positive; defaults to hidden / heads when omitted as 0 |
| `model.feedForwardDimension` | derived | Informational, not read by the cost model |
| `model.vocabularySize` | derived | Informational, not read by the cost model |
| `model.maxContextTokens` | required | Positive; used for context checks |
| `model.precision` | `fp16` | `fp32`, `tf32`, `fp16`, `bf16`, `fp8`, `int8`, `int4` |
| `model.bytesPerParameter` | from precision | Override for a precision outside the table |
| `model.kvDataType` | `model.precision` | Used only to derive `kvBytesPerScalar` when absent |
| `model.kvBytesPerScalar` | from `kvDataType` | Scalar width of the cache |
| `model.quantization` | `none` | Label only; the arithmetic reads `bytesPerParameter` |
| `model.activationBytesPerSequence` | `0` | Charged once per prefill step and once per sequence per decode step |
| `model.activeParameterFraction` | `1` | In `(0, 1]`; flops use parameters x fraction |
| `model.provenance` | `synthetic` | `synthetic`, `literature` or `measured` |

Byte-per-scalar defaults: `fp32` and `tf32` 4; `fp16` and `bf16` 2; `fp8` and `int8` 1; `int4`
0.5. An unknown precision without an explicit `bytesPerParameter` is an error, not a guess.

## `device.*`

| Key | Default | Rule |
|---|---|---|
| `device.id` | required | Non-empty; replicas get a suffix when there is more than one |
| `device.type` | `accelerator` | Free text |
| `device.memoryGiB` | required | Positive, binary GiB |
| `device.unusableMemoryFraction` | `0.05` | In `[0, 1)` |
| `device.memoryBandwidthGBs` | required | Positive, decimal GB/s |
| `device.achievedTFLOPS.<precision>` | required, at least one | Positive; `<precision>` must cover `model.precision` |
| `device.peakTFLOPS.<precision>` | none | Positive; informational, never used as a divisor |
| `device.idlePowerWatts` | `0` | Zero or more |
| `device.activePowerWatts` | `0` | Zero or more; if zero, energy is not modelled |
| `device.kernelLaunchOverheadMs` | `0` | Zero or more, converted to seconds |
| `device.hostToDeviceBandwidthGBs` | `0` | Zero means transfers are not modelled |
| `device.hostTransferLatencyMs` | `0` | Zero or more, converted to seconds |
| `device.provenance` | `synthetic` | `synthetic`, `literature` or `measured` |
| `device.note` | empty | Free text, echoed into the artifacts |

The declared achieved figures are what the cost model uses. Declaring a peak without an achieved
value is not enough, and the loader says so by name.

```properties
device.achievedTFLOPS.fp16=100
device.peakTFLOPS.fp16=150
```

Only the first line participates in a latency. The second is a label for a reader who wants to see
how far the achieved figure sits below the published one.

## `replicas.*` and `replica.*`

| Key | Default | Rule |
|---|---|---|
| `replicas.count` | `1` | At least 1; each replica gets its own device instance |
| `replica.maxBatchSize` | `64` | At least 1 |
| `replica.maxPrefillChunkTokens` | `2048` | Positive; `0` reads the whole prompt in one step |
| `replica.allowPreemption` | `true` | Boolean |
| `replica.victimOrder` | `newest-first` | `newest-first`, `lowest-priority`, `longest-context` |
| `replica.abortOnDeadlineMiss` | `false` | Boolean |
| `replica.tokensPerBlock` | `16` | At least 1 |
| `replica.runtimeOverheadGiB` | `0` | Zero or more |
| `replica.reservedActivationSlots` | `0` | Zero or more |

All replicas share the same specification. Their identifiers are the device id, with `-1`, `-2`
and so on appended when there is more than one. Two replicas never share one device object,
because each would size its cache from the whole memory.

Booleans accept `true`, `false`, `yes`, `no`, `on`, `off`, `1`, `0`, in any case.

## `workload.*`

### Arrival process

| Key | Default | Rule |
|---|---|---|
| `workload.pattern` | `poisson` | `fixed`, `poisson`, `bursty`, `time-varying`, `trace` |
| `workload.intervalSeconds` | `1` | Positive; used by `fixed` and `bursty` |
| `workload.ratePerMinute` | `6` | Positive; mean rate, or the default segment of a profile |
| `workload.burstSize` | `4` | At least 1; requests released at the same instant |
| `workload.rateProfileMinutes` | none | Ascending minutes; `N - 1` entries |
| `workload.rateProfilePerMinute` | none | Positive rates; `N` entries |
| `workload.maxRequests` | unlimited | At least 1 |
| `workload.stopArrivalsAfterSeconds` | none | Positive; arrivals stop, the run drains |

What each pattern does:

| Pattern | Arrival instants |
|---|---|
| `fixed` | One request every `intervalSeconds`, starting at 0 |
| `poisson` | Exponential gaps at `ratePerMinute`, first gap drawn at the start |
| `bursty` | `burstSize` requests at one instant, then a gap of `intervalSeconds` |
| `time-varying` | Poisson within each segment of the rate profile |
| `trace` | The instants recorded in `traceFile`, scaled by `traceTimeScale` |

A time profile is written as one more rate than boundary:

```properties
workload.pattern=time-varying
workload.ratePerMinute=30
workload.rateProfileMinutes=1,2
workload.rateProfilePerMinute=120,30,6
```

The first rate is the default; at minute 1 the rate becomes 30, at minute 2 it becomes 6.

### Request shape

| Key | Default | Rule |
|---|---|---|
| `workload.inputDistribution.name` | required unless trace | One of the names below |
| `workload.inputDistribution.<attribute>` | none | Attribute of that distribution, plus optional `min`, `max` |
| `workload.outputDistribution.name` | none | Same names |
| `workload.outputDistribution.<attribute>` | none | Same attributes |
| `workload.outputToInputRatio` | `0` | Positive alternative to an output distribution |
| `workload.outputJitterTokens` | `0` | Zero or more; uniform jitter around the correlated length |
| `workload.maxOutputTokens` | `4096` | At least 1; hard cap applied after every draw |

If an output distribution is given it wins; otherwise the output is `ratio x prompt`, with optional
jitter, clamped to `[1, maxOutputTokens]`.

### Controller knowledge

| Key | Default | Rule |
|---|---|---|
| `workload.prediction` | `oracle` | `oracle`, `biased-factor`, `constant`, `trace-observed` |
| `workload.predictionFactor` | `1` | Positive; used by `biased-factor` |
| `workload.predictionConstant` | `128` | At least 1; used by `constant` |

See [prediction modes](../README.md#prediction-modes) for the semantics. The predicted length is
stored per request and written to the artifact, so a run can report both what the controller
assumed and what happened.

### Service level and identity

| Key | Default | Rule |
|---|---|---|
| `workload.priority` | `0` | Integer; higher wins under `priority` scheduling |
| `workload.serviceClass` | `default` | Used to group SLO accounting |
| `workload.ttftTargetSeconds` | `0` | Zero or more; zero means not stated |
| `workload.tpotTargetSeconds` | `0` | Zero or more; zero means not stated |
| `workload.endToEndTargetSeconds` | `0` | Zero or more; zero means not stated |
| `workload.cancellationDelaySeconds` | `0` | Zero or more; zero means the client never gives up |
| `workload.modelId` | `model.id` | Must match the served model unless the pattern is `trace` |

Targets are relative to arrival, not to admission. A request that was preempted keeps its original
arrival, so its TTFT includes every queue episode of its life.

### Trace replay

| Key | Default | Rule |
|---|---|---|
| `workload.traceFile` | required for `trace` | Path, resolved against the scenario file |
| `workload.traceTimeScale` | `1` | Positive multiplier on every arrival instant |
| `workload.traceMaxRows` | unlimited | At least 1; rows read from the file |
| `workload.traceDescription` | derived | Written by the loader as a `# Trace:` line in `config.txt`, cannot be set |

See [trace replay](traces.md) for the column schema.

## `policy.*`

| Key | Default | Rule |
|---|---|---|
| `policy.admission` | `unlimited` | Comma-separated chain; applied in order; first refusal wins |
| `policy.queueLimit.maxWaitingRequests` | required for `queue-limit` | At least 1; a zero would refuse every arrival |
| `policy.cacheCapacity.headroomTokens` | `0` | Zero or more |
| `policy.predictedDeadline.tolerance` | `1` | Zero or more; fraction of the slack |
| `policy.routing` | `least-queued` | `round-robin`, `least-queued`, `least-predicted-work` |
| `policy.scheduling` | `fcfs` | `fcfs`, `priority`, `shortest-expected-first`, `shortest-oracle-first` |

An unknown name is an error that lists the accepted values. See [policies](policies.md) for the
rules and the oracle distinction.

## `cost.*`

| Key | Default | Rule |
|---|---|---|
| `cost.model` | `roofline-analytical` | Only implemented value |
| `cost.supportedMaxBatchSize` | largest `replica.maxBatchSize` | At least 1; above it, a step is flagged as extrapolated |
| `cost.supportedMaxStepTokens` | `16384` | At least 1; above it, a step is flagged |

The support envelope does not refuse a step; it attaches a warning to the estimate and the run, so
an extrapolated result is still produced and still labelled.

## A fully annotated scenario

The two committed files under `scenarios/` are the worked examples. The loaded one, reduced to its
decisions, is:

```properties
# Identity and budget
experiment.name=loaded-7b-memory-pressure
experiment.durationSeconds=30
experiment.seed=1
experiment.repetitions=3
experiment.outputDir=target/experiments
metrics.samplingIntervalSeconds=0.5

# A 7B decoder: 14e9 bytes of fp16 weights, 32 layers, 32 KV heads of 128
model.id=big-7b
model.parameterCount=7B
model.layers=32
model.hiddenDimension=4096
model.attentionHeads=32
model.kvHeads=32
model.headDimension=128
model.maxContextTokens=4096
model.precision=fp16

# An invented 24 GiB card: 700 GB/s, 100 achieved TFLOPS, 20 W idle, 150 W active
device.id=gpu-synthetic
device.memoryGiB=24
device.unusableMemoryFraction=0.05
device.memoryBandwidthGBs=700
device.achievedTFLOPS.fp16=100
device.peakTFLOPS.fp16=150
device.idlePowerWatts=20
device.activePowerWatts=150
device.kernelLaunchOverheadMs=0.02

# One replica: 32 sequences, 512-token prefill chunks, 16-token blocks
replicas.count=1
replica.maxBatchSize=32
replica.maxPrefillChunkTokens=512
replica.tokensPerBlock=16

# Two arrivals per second, prompts 128 to 2048 tokens, outputs 16 to 512
workload.pattern=poisson
workload.ratePerMinute=120
workload.inputDistribution.name=triangular
workload.inputDistribution.min=128
workload.inputDistribution.mode=1024
workload.inputDistribution.max=2048
workload.outputDistribution.name=triangular
workload.outputDistribution.min=16
workload.outputDistribution.mode=128
workload.outputDistribution.max=512
workload.maxOutputTokens=1024

# Interactive class with targets, oracle length knowledge
workload.prediction=oracle
workload.serviceClass=interactive
workload.ttftTargetSeconds=2
workload.tpotTargetSeconds=0.1
workload.endToEndTargetSeconds=30

policy.admission=unlimited
policy.routing=least-queued
policy.scheduling=fcfs
cost.model=roofline-analytical
```

Each choice has a consequence that the run shows: the weights leave about 10.5 GB for the cache,
which is 1,249 blocks of 16 tokens; the 512-token chunk is why a prefill step costs about 72 ms;
and the arrival rate is twice what the device serves, which is why the queue grows.
