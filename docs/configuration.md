---
description: "Every scenario property Docket reads: models, device profiles, deployment, arrival process, scheduling, access links, and SLO targets."
---
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
  least one sequence, and a block of at least one token;
- a deployment uses one device form: `devices` with per-profile blocks, or the flat `device.*`
  block, never both;
- every device profile declares an achieved throughput for the served precision, and every entry of
  `replicas` names a profile that `devices` declares;
- a disaggregated deployment (`deployment.disaggregated=true`) states its shape with
  `deployment.prefillReplicas` and `deployment.decodeReplicas`, needs at least one of each, and is
  refused if it also sets `replicas` or `replicas.count`;
- a bearer a device names exists and has a positive capacity, and its latency and payload sizes are
  not negative.

## Units

| Quantity | Unit and convention |
|---|---|
| Memory | Binary: KiB, MiB, GiB (`device.memoryGiB`, `replica.runtimeOverheadGiB`) |
| Bandwidth | Decimal: GB/s |
| Link capacity | Decimal: Mbps, 1e6 bit/s, converted to bytes once at the profile |
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
| `model.quantization` | `none` | Label only; the arithmetic reads `bytesPerParameter` unless the kernel is on |
| `model.activationBytesPerSequence` | `0` | Charged once per prefill step and once per sequence per decode step |
| `model.activeParameterFraction` | `1` | In `(0, 1]`; flops use parameters x fraction |
| `model.quantizedKernel` | `false` | Opt-in arithmetic beyond the byte width; off keeps the plain roofline |
| `model.computePrecision` | `model.precision` | Precision the matmul runs at when the kernel is on; the device must declare its achieved throughput |
| `model.dequantFlopsPerScalar` | `0` | Unpacking ops added per weight per step |
| `model.computeBytesPerScalar` | `0` | Compute-format width; its excess over the storage width is charged as expansion bytes |
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

### Several machines: `devices` and `device.<profile>.*`

A deployment of unlike hardware lists its machines by profile name and reads each one from its own
block:

```properties
devices=phone,server
device.phone.memoryGiB=12
device.phone.memoryBandwidthGBs=68
device.phone.achievedTFLOPS.fp16=0.045
device.phone.link=cellular
device.server.memoryGiB=24
device.server.memoryBandwidthGBs=700
device.server.achievedTFLOPS.fp16=100
```

| Key | Default | Rule |
|---|---|---|
| `devices` | empty | Comma-separated profile names, each used at least once; empty keeps the flat form above |
| `device.<profile>.id` | the profile name | Optional here, required in the flat form; only needed to rename a machine in the artifacts |
| `device.<profile>.link` | empty | Name of the `link.<name>` block its clients reach it through |

Every other key of the flat table is read the same way under the profile prefix, and each profile is
checked on its own: a phone that declares no achieved throughput for the served precision stops the
load with the failing key named. The two forms do not mix, because a deployment that read some
figures from a flat block and others from a profile would report a device no file states; setting
`devices` alongside a flat `device.id` is refused, and so is listing one profile twice, which is
what `replicas` is for.

`config.txt` prints one line per profile, and the console header says how many there are.

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
| `replica.speculativeDecoding` | `false` | Opt-in; verify several draft tokens per step instead of emitting one |
| `replica.draftTokens` | `2` | At least 1 when speculative decoding is on |
| `replica.acceptanceRate` | `0.7` | In `[0, 1)`; expected kept tokens are `(1 - a^(k+1)) / (1 - a)` rounded |
| `replica.prefixCache` | `false` | Opt-in prompt-prefix reuse; refused under disaggregation |

All replicas share the same specification. Their identifiers are the device id, with `-1`, `-2`
and so on appended when there is more than one. Two replicas never share one device object,
because each would size its cache from the whole memory.

### A fleet: `replicas` and `replica.<profile>.*`

`replicas.count` clones one template. A fleet instead names one machine per replica, drawn from the
profiles `devices` declares, so a phone, a small server and an accelerator node can serve the same
model side by side:

```properties
devices=phone,server
replicas=phone,server,server
replica.server.maxBatchSize=64
replica.phone.maxBatchSize=1
```

| Key | Default | Rule |
|---|---|---|
| `replicas` | empty | Comma-separated profile names, one entry per replica; every entry must be in `devices` |
| `replica.<profile>.maxBatchSize` | the `replica.*` value | At least 1 |
| `replica.<profile>.maxPrefillChunkTokens` | the `replica.*` value | Positive |
| `replica.<profile>.allowPreemption` | the `replica.*` value | Boolean |
| `replica.<profile>.victimOrder` | the `replica.*` value | Same three labels |
| `replica.<profile>.abortOnDeadlineMiss` | the `replica.*` value | Boolean |
| `replica.<profile>.tokensPerBlock` | the `replica.*` value | At least 1 |
| `replica.<profile>.runtimeOverheadGiB` | the `replica.*` value | Zero or more |
| `replica.<profile>.reservedActivationSlots` | the `replica.*` value | Zero or more |
| `replica.<profile>.speculativeDecoding` | the `replica.*` value | Boolean |
| `replica.<profile>.draftTokens` | the `replica.*` value | At least 1 when on |
| `replica.<profile>.acceptanceRate` | the `replica.*` value | In `[0, 1)` |
| `replica.<profile>.prefixCache` | the `replica.*` value | Boolean |

The batch ceiling and the cache block are per-machine choices rather than per-deployment ones: a
phone runs one sequence at a time and a server batches dozens, so a fleet that shared one ceiling
would compare a placement against a deployment nobody runs. A profile may appear more than once,
which means several machines of that kind; those replicas are numbered `-1`, `-2` and so on, because
a cache refusal has to say which machine ran out. `replicas` and `replicas.count` together are
refused rather than resolved by precedence, and an override key outside the eight above is reported
by name instead of being left at the fleet default.

Booleans accept `true`, `false`, `yes`, `no`, `on`, `off`, `1`, `0`, in any case.

## `deployment.*`: disaggregating prefill from decode

By default every replica runs both phases, which is the colocated deployment above. A scenario can
instead split the two phases across two pools, so a prompt is prefilled on one set of machines, its
finished cache crosses an interconnect, and a separate set generates the tokens into a batch no prefill
ever joins. The feature is opt-in and read entirely from `deployment.*`:

| Key | Default | Rule |
|---|---|---|
| `deployment.disaggregated` | `false` | When `true`, the fleet is built from the two counts below instead of `replicas` |
| `deployment.prefillReplicas` | required when disaggregated | At least 1; clones of the first device profile |
| `deployment.decodeReplicas` | required when disaggregated | At least 1; clones of the second profile, or the first when only one is declared |
| `deployment.fabric` | empty | Name of the `link.<name>` block the finished cache crosses; empty hands off instantly |

```properties
devices=compute,memcard
device.compute.achievedTFLOPS.fp16=150
device.compute.memoryBandwidthGBs=700
device.compute.memoryGiB=24
device.memcard.achievedTFLOPS.fp16=60
device.memcard.memoryBandwidthGBs=3350
device.memcard.memoryGiB=80
deployment.disaggregated=true
deployment.prefillReplicas=1
deployment.decodeReplicas=3
deployment.fabric=fabric
link.fabric.capacityMbps=400000
link.fabric.latencyMs=0.5
```

The profile that prefills is the first name in `devices` and the one that decodes is the second, so
the two stages are heterogeneous by construction when two profiles are declared and identical when one
is. A deployment that sets `deployment.disaggregated` alongside `replicas` or `replicas.count` is
refused, because the split states its shape with the two counts and a file that set both would describe
two different machines. Each stage replica carries a role-tagged identifier (`...-p1`, `...-d1`), so a
split on one machine kind still gives every device a distinct cache budget.

The fabric is the store-and-forward bearer the hand-off charges onto. It reuses the `link.<name>` block
(see below) for capacity and latency, and moves `cachedTokens x kvBytesPerToken` bytes, the size the
model's cache arithmetic already knows; the link's own `bytesPerInputToken` plays no part, because the
payload is a key-value cache rather than a prompt. Leaving `deployment.fabric` empty models an intra-node
hand-off with no wire, so the first-token time carries only the prefill queue and the transfer. The batch
ceiling is set per profile (`replica.<profile>.maxBatchSize`) because the two phases want different batch
shapes. See [the disaggregation example](../examples/13-prefill-decode-disaggregation/README.md) for the
measured comparison against a colocated fleet at a fixed device budget.

## `link.<name>.*`

A device block that writes `device.<profile>.link=<name>` says that its clients reach it over one
hop of its own, described by the block `<name>`:

```properties
device.phone.link=cellular
link.cellular.capacityMbps=20
link.cellular.latencyMs=40
link.cellular.provenance=literature
link.cellular.note=downlink budget of a macro cell at medium signal quality
```

| Key | Default | Rule |
|---|---|---|
| `link.<name>.capacityMbps` | required | Positive, decimal Mbps; the raw bearer, shared by everything on it |
| `link.<name>.latencyMs` | `0` | Zero or more, one-way propagation |
| `link.<name>.bytesPerInputToken` | `4` | Above zero; bytes one prompt token costs on the uplink |
| `link.<name>.bytesPerOutputToken` | `4` | Above zero; bytes one generated token costs on the downlink |
| `link.<name>.provenance` | `synthetic` | `synthetic`, `literature` or `measured`, and it counts toward the overall label |
| `link.<name>.note` | empty | Free text, echoed into the artifacts |

The four byte figures are the whole payload model. A prompt travels as one message of
`inputTokens x bytesPerInputToken` bytes and an answer as one message of
`generatedTokens x bytesPerOutputToken` bytes, which is the non-streaming reading of the API and
the conservative one: a stream that sends each token as it appears empties no earlier. Four bytes
per token sits near the cost of a token id on a wire that already carries its own framing; a client
that sends a JSON body with metadata pays more, and says so with the key.

Each direction is a first-in first-out store-and-forward queue, the same rule a `NetworkLink` of
PureEdgeSim applies to a file transfer: a message starts as soon as the bearer is free, holds it
for its size divided by the raw capacity, and is readable one propagation time later. Transfers
queue rather than share the bearer, so congestion shows up as waiting time behind the requests
ahead, not as a slower service rate for everyone. The two directions are independent, as they are
on any full-duplex bearer.

Two devices naming the same link hold one queue, because an access point serving three phones is
one bottleneck and not three independent ones. A scenario that names no bearer at all charges no
network time and reports the same rows as before the link existed, which is what keeps every figure
measured before 1.1 comparable.

What the hop costs a request is written in the columns `uplink_s` and `downlink_s`, and the client
side of the answer is `delivery_time_s`, the device instant plus the return flight. `ttft_s` and
`e2e_s` are already measured from the release of the request, so the uplink is inside them; the
separate columns exist to show how much of a slow answer was the wire. See
[metrics.md](metrics.md) for the rows and [architecture.md](architecture.md) for where the engine
holds a prompt between the gateway and the replica.

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
| `workload.ttftTargetSeconds.<class>` | the global value | Zero or more, per service class; `tpot` and `endToEnd` take the same suffix |
| `workload.cancellationDelaySeconds` | `0` | Zero or more; zero means the client never gives up |
| `workload.modelId` | `model.id` | Must match the served model unless the pattern is `trace` |

Targets are relative to arrival, not to admission. A request that was preempted keeps its original
arrival, so its TTFT includes every queue episode of its life.

A class-keyed target is where an operator states what a class is promised, which is the only way a
replayed trace can report goodput: the trace says what arrived, not what was guaranteed. Precedence
is per field and goes row, then class, then global, so a trace that carries `ttft_target_s` keeps
its own value and a class that leaves a field at `0` inherits the global one. A class that no
request of the workload belongs to is refused at load, because a target nobody can meet is worse
than an absent one.

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
| `policy.admission.class` | unset | Fully qualified class implementing `AdmissionPolicy`; wins over `policy.admission` |
| `policy.queueLimit.maxWaitingRequests` | required for `queue-limit` | At least 1; a zero would refuse every arrival |
| `policy.cacheCapacity.headroomTokens` | `0` | Zero or more |
| `policy.predictedDeadline.tolerance` | `1` | Zero or more; fraction of the slack |
| `policy.routing` | `least-queued` | `round-robin`, `least-queued`, `least-predicted-work` |
| `policy.routing.class` | unset | Fully qualified class implementing `RoutingPolicy`; wins over `policy.routing` |
| `policy.scheduling` | `fcfs` | `fcfs`, `priority`, `shortest-expected-first`, `shortest-oracle-first` |
| `policy.scheduling.class` | unset | Fully qualified class implementing `SchedulingPolicy`; wins over `policy.scheduling` |

An unknown name is an error that lists the accepted values. A class key must name a class on the
run's classpath with a public no-argument constructor; the error names the setting and the class.
See [policies](policies.md) for the rules, the oracle distinction and the loading hook.

## `cost.*`

| Key | Default | Rule |
|---|---|---|
| `cost.model` | `roofline-analytical` | `roofline-analytical` or `roofline-calibrated` |
| `cost.calibration.source` | required for `roofline-calibrated` | Non-empty; names the benchmark and hardware the coefficients were fitted to |
| `cost.supportedMaxBatchSize` | largest `replica.maxBatchSize` | At least 1; above it, a step is flagged as extrapolated |
| `cost.supportedMaxStepTokens` | `16384` | At least 1; above it, a step is flagged |

Both models use the same roofline arithmetic. `roofline-analytical` takes the device figures on
trust and says so. `roofline-calibrated` assumes the achieved throughput and the effective
bandwidth were fitted to a benchmark, which `cost.calibration.source` must name; a calibrated
model without that line is refused at load time. The scenario label then follows
[the provenance rules](cost-model.md#provenance-and-the-calibration-contract): measured profiles
priced by a calibrated model report `measured`, and the same profiles priced by the analytical
model report `synthetic`.

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
