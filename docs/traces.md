# Trace replay

A workload can be generated from a distribution or replayed from a recorded trace. Replaying is how
a result stops depending on an invented arrival process: the prompt lengths, the generated lengths
and the instants at which clients pressed enter come from measurements instead of from a sampler.

This page covers the file format, the transformations the loader applies, what a row controls once
it is replayed, and how a public trace maps onto the columns. It also states the limit of the
exercise: a replay changes the traffic, not the cost model.

## What ships in this repository

Four trace files are committed under [`examples/real-traces/data/`](../examples/real-traces/data):
2,000-request windows cut from the Azure LLM inference trace, the Qwen-Bailian trace, the Mooncake
conversation trace recorded on Kimi traffic, and BurstGPT. Each has a downloader, a converter, a
paired scenario, an archived run under `examples/real-traces/results/` and a SHA-256 record in
`manifest.json`. [The example README](../examples/real-traces/README.md) is the tour; this page is
the format reference.

The two scenarios under `scenarios/` still generate their traffic. The replay examples are a
separate directory because they answer a different question: whether the engine behaves under a
measured arrival process, not whether the engine predicts a particular deployment.

A trace supplies arrivals and lengths; it does not supply the time a step takes on the device.
That number still comes from the [roofline model](cost-model.md), which is uncalibrated here, so
with the model, device and cost model this repository ships, `overallProvenance` in the console
stays `synthetic` even when the workload is recorded. Replaying a real trace makes the traffic
honest; it does not make the latencies measured.

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

None of the four public traces states targets, so the scenario states them per class instead:
`workload.ttftTargetSeconds.<class>`, `workload.endToEndTargetSeconds.<class>` and
`workload.tpotTargetSeconds.<class>`. A row that carries no target inherits the triple of its
service class, which is how
[`qwen-traceA-objectives.properties`](../examples/real-traces/scenarios/qwen-traceA-objectives.properties)
turns goodput from not applicable into a measured 0.8175. Those triples are an operator assumption,
and the scenario says so where it states them.

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
| Targets | `ttft_target_s`, `e2e_target_s`, `tpot_target_s` | Made absolute at arrival; a row that states none inherits the class targets of the scenario |
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

The four mappings used by [`convert_traces.py`](../examples/real-traces/convert_traces.py) are
below. Column names follow the releases as published; every release renames something eventually,
so check the header of the file you actually downloaded instead of trusting this table. The
converter is committed next to the data because the converter is part of the method, and
`manifest.json` records the window, the dropped rows and the SHA-256 of the source file.

| Trace | Time base | Lengths | Mapping |
|---|---|---|---|
| Azure LLM inference trace (AzurePublicDataset) | Wall-clock `TIMESTAMP`, `YYYY-MM-DD HH:MM:SS.fffffff` | `ContextTokens`, `GeneratedTokens` | Parse the seven-digit fraction to seconds, subtract the first instant; lengths map directly |
| Qwen-Bailian | `timestamp` in seconds | `input_length`, `output_length` | Subtract the first instant; `type` becomes the service class; `chat_id` becomes `session_id` |
| Mooncake (Kimi) | `timestamp` in milliseconds | `input_length`, `output_length` | Divide by 1000, subtract the first instant; `hash_ids` identify a prefix rather than a session, so they are not mapped |
| BurstGPT | `Timestamp` in seconds | `Request tokens`, `Response tokens` | Subtract the first instant; `Log Type` becomes the service class, so the accounting can separate API calls from conversations |
| ShareGPT conversations | None: a conversation has an order, not a clock | Prompt and response text per turn, tokenized by you | Timings must be synthesized, so this trace calibrates lengths and session structure, not arrivals |

No length is clamped, rounded down or resampled. A row that cannot be parsed is counted in the
manifest's `dropped` record and reported, not silently skipped. The arrival instants of a window
are rebased to zero, so each replay starts at simulated time 0.

Cautions that apply to every row above:

- **Tokenizers differ.** A token count produced by another model's tokenizer is an approximation
  of this model's count, and the error is systematic, not random. Record which tokenizer produced
  the counts next to the trace file and the scenario that replays it.
- **A conversation trace is not a trace of arrivals.** ShareGPT says which turns belong together
  and in what order; it does not say how long a user thought before typing. Replaying it with
  invented gaps is a semi-synthetic workload, and the artifact should be read that way. Qwen and
  Mooncake do carry arrival instants, so their replays are not in this category.
- **Prefix hashes are evidence, not input.** Qwen's and Mooncake's `hash_ids` describe the prefix
  a caching engine could reuse. This simulator has no cross-request prefix cache, so the converter
  keeps Qwen's `chat_id` as `session_id`, does not map `hash_ids`, and the replay does not use them
  for scheduling.

## Checking a replay

Four places reconcile what was read with what was run:

1. `config.txt` carries a `# Trace:` line built by the loader, for instance
   `# Trace: trace example.csv, 4 rows read, 4 replayed, arrival instants scaled by 0.2500`.
2. The console prints `workload : trace, model big-7b` and the first repetition's `offered` count.
3. Per-row evidence lives in `repNN/requests.csv`: `arrival_time_s`, `input_tokens` and
   `output_tokens` of every released request, which can be joined back to the trace to confirm that
   the replay did not reorder or rescale anything it should not have.
4. [`verify_replays.py`](../examples/real-traces/verify_replays.py) does that join as a multiset of
   (arrival, prompt, requested output, service class), reports the largest arrival difference and
   every unmatched or extra row, and ends with `REPLAY_CHECK OK`. The four committed replays
   reconcile with 2,000 rows each and a maximum difference of 0 s; the archived output is
   `examples/real-traces/results/replay-check.txt`.

If `offered` is smaller than the rows replayed, the run ended before the trace did. If any row
carries `no_replica_for_model`, the `model_id` column does not match the scenario's `model.id`.
