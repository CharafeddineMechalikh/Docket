# Cost model

The only implemented execution model is `roofline-analytical`. It turns a step description into
seconds, and it also reports the flops, the bytes, the binding term and whether the answer is
inside the envelope it was checked against. This page gives the formulas, the power model and the
calibration contract.

## The roofline

```text
step_seconds = max( F / Φ , B / β ) + δ
```

| Symbol | Meaning | Comes from |
|---|---|---|
| `F` | Floating-point operations of the step | Model shape and step contents |
| `B` | Bytes moved through device memory | Model shape and step contents |
| `Φ` | Achieved throughput, in flops per second | `device.achievedTFLOPS.<precision>` |
| `β` | Achieved memory bandwidth, in bytes per second | `device.memoryBandwidthGBs` |
| `δ` | Kernel launch overhead, in seconds | `device.kernelLaunchOverheadMs` |

Only the achieved throughput is used. A published peak may be declared under
`device.peakTFLOPS.*`, but it never appears in a division.

The binding term is chosen as:

| Condition | Term | Reading |
|---|---|---|
| `B / β > F / Φ` and `B / β >= δ` | `MEMORY` | The step is bandwidth-bound; batching or a smaller cache helps |
| `δ > F / Φ` and `δ > B / β` | `OVERHEAD` | The step is too small to amortise the launch; batch more |
| otherwise | `COMPUTE` | The step is compute-bound; fewer flops or more throughput helps |

When the term is `OVERHEAD`, an estimate warning is attached, because in that regime the duration
is set by a constant and says little about the model.

## Prefill

Given `T` prompt tokens to process and `C` tokens already cached:

```text
F_prefill = 2 * P_active * T
          + 4 * L * d_kv * ( T * C + T * (T + 1) / 2 )

B_prefill = W
          + kv_bytes_per_token * ( C + T )
          + A
```

| Symbol | Meaning |
|---|---|
| `P_active` | `parameterCount * activeParameterFraction` |
| `L` | `model.layers` |
| `d_kv` | `kvHeads * headDimension` |
| `W` | Weight bytes, `parameterCount * bytesPerParameter` |
| `A` | `model.activationBytesPerSequence` |
| `kv_bytes_per_token` | `2 * L * d_kv * kvBytesPerScalar` |

The first term is the two projections (forward and backward-style matrix multiply count, two flops
per parameter per token). The second is attention: the cached prefix costs `T * C`, and the new
tokens attend to each other at `T * (T + 1) / 2`. The factor four covers query-key and
query-value products.

Every prefill chunk re-reads the weights, so chunking a prompt into `k` chunks multiplies the
weight traffic by `k`. That is the cost side of chunked prefill; the benefit is that decodes are
not blocked behind the whole prompt.

## Decode

For a batch of `N` sequences whose contexts hold `S` tokens in total:

```text
F_decode = 2 * P_active * N
         + 4 * L * d_kv * S

B_decode = W
         + kv_bytes_per_token * S
         + kv_bytes_per_token * N
         + A * N
```

Decode flops are tiny next to the bytes: one token per sequence, but the weights are read in full
and the whole cache is read, plus one new key/value pair per sequence. This is why decode is
memory-bound on every accelerator and why batching raises throughput until the cache or the batch
ceiling stops it.

The model charges the weights once per step, which assumes a fused kernel, and charges the whole
cache as a full read, which is the pessimistic end of a paged layout. Both assumptions are listed
in the cost model's own warnings and echoed into `config.txt`.

## Worked example

The loaded scenario, with a 512-token prefill chunk on an empty cache:

```text
P              7e9 parameters, fp16
W              14.0e9 bytes
kv/token       2 * 32 * 4096 * 2 = 524,288 bytes (512 KiB)
T              512
Φ              100e12 flops/s
β              700e9 bytes/s
δ              0.02 ms

F = 2*7e9*512 + 4*32*4096*(512*513/2)
  = 7.168e12 + 6.885e10
  = 7.237e12 flops
B = 14.0e9 + 524,288*512
  = 14.27e9 bytes

F/Φ = 72.4 ms
B/β = 20.4 ms
step = max(72.4, 20.4) + 0.02 ms = 72.4 ms, COMPUTE-bound
```

The same step with the whole 2048-token prompt unchunked costs about 290 ms of compute and 21.6 ms
of memory, which is why the loaded scenario uses 512-token chunks.

A decode iteration that reads the same weights is at least `14.0e9 / 700e9 = 20 ms` of memory
time no matter how small the batch, plus the cache read. A batch of 20 sequences at 1,000 context
tokens each reads `20 * 1,000 * 524,288 = 10.5e9` bytes of cache, which adds 15 ms. That is the
regime the loaded scenario reports: a decode step around 35 ms to 50 ms depending on the batch.

## Extrapolation and warnings

The model attaches warnings to the run when:

| Condition | Warning |
|---|---|
| More than `cost.supportedMaxBatchSize` sequences | The batch exceeds the envelope the form was checked against |
| More than `cost.supportedMaxStepTokens` tokens in one step | The step exceeds the token envelope |
| `kvBytesPerScalar < bytesPerParameter` | The cache is quantized more aggressively than the weights; the arithmetic term is unchanged but a real backend differs |
| `OVERHEAD` binding term | The launch overhead dominates the step |

Extrapolation does not refuse the step. The estimate is produced and labelled, because refusing
would make it impossible to explore the edge of the envelope deliberately.

## Power and energy

`GpuPowerModel` models two levels:

```text
energy = active_power * active_seconds + idle_power * idle_seconds
```

An interval is active when at least one sequence occupies the device. The per-request share of an
active interval is proportional to the sequences that took part in it, so every joule of active
energy is charged exactly once and the per-request rows sum to the active device energy. Idle
energy stays on the device total and is not attributed to a request.

With `device.activePowerWatts=150` and `device.idlePowerWatts=20`, a run that is busy for 29.9 s
of a 30 s repetition reports about `150 * 29.9 + 20 * 0.1 = 4,487 J`, which is the order of the
4,485 J the loaded scenario prints.

Energy is reported in joules and watt-hours in the artifacts, per request and per run.

## Provenance and the calibration contract

`ExecutionCostModel` exposes five facts about itself:

| Method | `roofline-analytical` | `roofline-calibrated` |
|---|---|---|
| `name()` | `roofline-analytical` | `roofline-calibrated` |
| `provenance()` | `synthetic` | `measured` |
| `isCalibrated()` | `false` | `true` |
| `calibrationSource()` | `none` | The line the scenario gives in `cost.calibration.source` |
| `warnings()` | Four caveats | The fitted range and the effective-bandwidth caveat |

Both classes write `name()` into every artifact row. The calibrated one refuses to load without
`cost.calibration.source`, and its warnings say that a device-wide coefficient transfers from the
fitted model and backend only as far as those stay comparable.

The scenario reports the weakest provenance among the model, the device and the cost model. A
model that claims to be calibrated while the device is synthetic still yields a synthetic run, and
the console says so.

A calibrated implementation must:

1. return `isCalibrated() == true`;
2. name the engine, device and workload used for the fit in `calibrationSource()`;
3. keep returning an estimate whose binding term is computed the same way, so a comparison across
   calibrated and uncalibrated runs remains interpretable;
4. add its own caveats, for instance the batch range of the fit.

`CalibratedRooflineCostModel` keeps the arithmetic of the analytical model and changes only what
the estimate calls itself, so point 3 holds by construction. The committed
[calibration example](../examples/calibration/README.md) fits its two coefficients to a
llama-bench capture on one CPU and reports the residual against that capture.

Scenarios that keep the analytical model remain estimates of a hardware limit under stated
assumptions, not measurements of a serving engine.
