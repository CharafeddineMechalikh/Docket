# Trace replay

A workload can be generated from a distribution or replayed from a recorded trace. Replaying is how
a result stops depending on an invented arrival process: the prompt lengths, the generated lengths
and the instants at which clients pressed enter come from measurements instead of from a sampler.

This page covers the file format, the transformations the loader applies, what a row controls once
it is replayed, and how a public trace maps onto the columns. It also states the limit of the
exercise: a replay changes the traffic, not the cost model.

## What ships in this repository

No trace file is committed. Both scenarios under `scenarios/` generate their traffic, and no
provider trace (OpenAI, Anthropic, Gemini or otherwise) is used anywhere. The replay path is
implemented, validated and tested, but the repository does not claim a trace-driven result it has
not run. A public trace is the first item on the roadmap in the [README](../README.md#roadmap).

The reason is provenance, not effort. A trace supplies arrivals and lengths; it does not supply the
time a step takes on the device. That number still comes from the [roofline model](cost-model.md),
which is uncalibrated here, so with the model, device and cost model this repository ships,
`overallProvenance` in the console stays `synthetic` even when the workload is recorded. Replaying a
real trace makes the traffic honest; it does not make the latencies measured.

## File rules

The file is UTF-8 CSV with a header line. It is read by column **name**, lower-cased and trimmed,
so a file exported by another tool can be replayed without rearranging anything. Column order is
free. A repeated header name is refused, because one of the two copies would silently win.

- Blank lines are skipped, and so are lines that begin with `#`, which makes a trace
  self-documenting.
- Fields are split by commas, with double quotes honoured and `""` read as a literal quote, so a
  service class holding a comma survives the round trip through `requests.csv`.
- Token counts are parsed as numbers and rounded to the nearest integer, so a trace that stores a
  mean projected length still loads.
- Errors name the file and the physical line number.

### Required columns

| Column | Meaning | Constraint |
|---|---|---|
| `arrival_time_s` | Arrival instant in seconds from the start of the replay | Zero or more, non-decreasing down the file |
| `model_id` | Model the request targeted | Must match the model a replica serves, or the row is refused |
| `input_tokens` | Prompt length | At least 1 |
| `output_tokens` | Observed generated length | At least 1 |

A missing required column is an error that lists the header the file actually has, so a renamed
column is found immediately rather than read as a default.

### Optional columns

Each defaults to what an unstressed, single-tenant request looks like.

| Column | Default | Meaning |
|---|---|---|
| `priority` | `0` | Scheduling priority, higher wins |
| `service_class` | `default` | Class used for SLO accounting |
| `session_id` | empty | Session identifier, kept in the record |
| `ttft_target_s` | `0` | TTFT target, relative to arrival; `0` states none |
| `e2e_target_s` | `0` | End-to-end target, relative to arrival; `0` states none |
| `tpot_target_s` | `0` | Per-token target; `0` states none |
| `cancellation_delay_s` | `0` | Client abandonment delay from arrival; `0` means never |

A target of `0` means "not stated", so the request is not counted in that objective at all. It is
not counted as met, and not counted as missed. See [Metrics](metrics.md#service-levels-and-goodput).

## Rows must be sorted

The loader keeps the file order and the plan refuses a row that arrives before its predecessor,
naming both row indices and both instants. Sorting is the trace's job because the order is
evidence: two requests at the same instant are tie-broken by their sequence, and reordering a trace
would change which one is considered first. Convert the trace sorted, and keep it sorted.

The whole file is read into memory. `workload.traceMaxRows` cuts the number of rows kept, but only
after reading, so a multi-gigabyte file should be downsized before it is handed to the simulator
rather than truncated by the key.

## Time scaling

`workload.traceTimeScale` multiplies every arrival instant. `0.25` replays the trace four times
faster, `4` four times slower. The factor must be positive and finite.

The next arrival is scheduled at `max(0, next_instant - clock)`, so scaling and rounding can never
produce a negative delay: a row whose scaled instant has already passed is released at the current
instant instead of being dropped. Scale a trace far enough and many rows collapse into the same
instant, which is a genuine burst as far as the service is concerned. That is a property of the
replay, not a bug, and it is why the queue, not the arrival curve, decides what such a run measures.

Two interactions with the experiment settings are worth stating:

- **Duration.** An arrival scheduled after `experiment.durationSeconds` never happens. The
  `# Trace:` line in `config.txt` counts the rows loaded, not the rows released, so compare it with
  the `offered` column of `summary.csv` to see how much of the trace the run actually reached.
  Lengthen the duration or lower the time scale to cover the whole file.
- **`workload.stopArrivalsAfterSeconds` is ignored for a trace.** The trace ends where its rows end.
  Use `workload.traceMaxRows` to cut it, and `experiment.durationSeconds` to stop the clock.
  `workload.maxRequests` is raised to the row count by the loader when the trace holds more rows
  than the cap, so it never truncates a trace silently.

## What one row controls

| Field | Comes from | Note |
|---|---|---|
| Prompt length | `input_tokens` | No distribution is consulted |
| Generated length | `output_tokens` | `maxOutputTokens` is raised to the larger of the recorded length and the configured cap, so a recorded length is never truncated |
| Predicted length | `output_tokens` | `workload.prediction=trace-observed` is what a replay can honestly give a controller |
| Arrival instant | `arrival_time_s` x `traceTimeScale` | The engine clock, in seconds |
| Priority | `priority` | Used by the `priority` scheduling policy |
| Service class | `service_class` | Selects the SLO that is accounted |
| Targets | `ttft_target_s`, `e2e_target_s`, `tpot_target_s` | Made absolute at arrival |
| Cancellation | `cancellation_delay_s` | Made absolute at arrival |
| Session | `session_id` | Recorded, never used to route |

`model_id` decides routing. The deployment hosts one model, so a row naming a different model is
refused with `no_replica_for_model` and still counted as offered. A mixed-model trace has to be
split into one file per model and replayed as one run per model; the simulator does not load
balance across models, and pretending otherwise would need a second service.

The recorded `output_tokens` is the ground truth of the replay, so it is also what the controller
would know after the fact. `trace-observed` is the only prediction mode under which a length-aware
policy is not cheating: `oracle` would be the same number, `biased-factor` and `constant` would be
a deliberate handicap. For a trace, `oracle` and `trace-observed` produce identical predictions,
which is worth remembering before reading a policy comparison as evidence about prediction quality.

## A worked example

```csv
arrival_time_s,model_id,input_tokens,output_tokens,service_class,ttft_target_s,e2e_target_s
0.000,big-7b,512,128,interactive,0.30,4.0
0.410,big-7b,2048,64,batch,0,0
0.410,big-7b,64,512,interactive,0.30,8.0
1.207,big-7b,1024,256,batch,0,0
```

```properties
experiment.durationSeconds=30
workload.pattern=trace
workload.traceFile=../traces/example.csv
workload.traceTimeScale=1
workload.prediction=trace-observed
```

The second and third rows share an instant. They are released in file order, so the 2048-token
prompt is considered before the 64-token one, and the tie-break is a property of the trace rather
than of the engine's random stream. If both fit, admission decides on the queue as it stands after
the first is handled.

## Mapping a public trace

Three public traces are the usual candidates. The column names below follow the releases as
published; every release renames something eventually, so check the header of the file you actually
downloaded instead of trusting this table. Conversion is a short script, and it should be committed
next to the run that uses it, because the converter is part of the method.

| Trace | Time base | Lengths | Mapping |
|---|---|---|---|
| Azure LLM inference trace (AzurePublicDataset) | Millisecond timestamps, conversation and code subsets | `ContextTokens`, `GeneratedTokens` | `arrival_time_s = (timestamp - first) / 1000`; `input_tokens = ContextTokens`; `output_tokens = GeneratedTokens` |
| BurstGPT | Timestamp in seconds | `Request tokens`, `Response tokens`, per log type | `arrival_time_s = timestamp - first`; lengths map directly; map `Log Type` to `service_class` so the accounting can separate the workloads |
| ShareGPT conversations | None: a conversation has an order, not a clock | Prompt and response text per turn, tokenized by you | Timings must be synthesized, so this trace calibrates lengths and session structure, not arrivals |

Two cautions that apply to all three:

- **Tokenizers differ.** A token count produced by another model's tokenizer is an approximation
  of this model's count, and the error is systematic, not random. Record which tokenizer produced
  the counts next to the trace file and the scenario that replays it.
- **A conversation trace is not a trace of arrivals.** ShareGPT says which turns belong together
  and in what order; it does not say how long a user thought before typing. Replaying it with
  invented gaps is a semi-synthetic workload, and the artifact should be read that way.

## Checking a replay

Three places reconcile what was read with what was run, and they should agree:

1. `config.txt` carries a `# Trace:` line built by the loader, for instance
   `# Trace: trace example.csv, 4 rows read, 4 replayed, arrival instants scaled by 0.2500`.
2. The console prints `workload : trace, model big-7b` and the first repetition's `offered` count.
3. Per-row evidence lives in `repNN/requests.csv`: `arrival_time_s`, `input_tokens` and
   `output_tokens` of every released request, which can be joined back to the trace to confirm that
   the replay did not reorder or rescale anything it should not have.

If `offered` is smaller than the rows replayed, the run ended before the trace did. If any row
carries `no_replica_for_model`, the `model_id` column does not match the scenario's `model.id`.
