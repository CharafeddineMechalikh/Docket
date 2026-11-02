# Metrics and artifacts

This page defines every number the simulator reports, how service levels are accounted, and the
schema of each artifact. It is the reference for reading a result or plotting one.

## Time origin

Every timestamp is on the simulation clock, in seconds. Latencies are measured from the **arrival
at the service**, not from admission, because that is the instant the client started waiting. A
request that was preempted keeps its original arrival, so a queue episode after a preemption adds
to its TTFT rather than restarting the clock.

## Per-request metrics

| Metric | Formula | Defined for |
|---|---|---|
| TTFT `ttft_s` | `first_token_time - arrival_time` | Requests that emitted at least one token |
| TPOT `tpot_s` | `(last_token_time - first_token_time) / (generated_tokens - 1)` | Requests with at least two tokens |
| Mean ITL `mean_itl_s` | Mean of the per-iteration gaps between emitted tokens | Requests with at least two tokens |
| Max ITL `max_itl_s` | Largest single gap | Requests with at least two tokens |
| End-to-end `e2e_s` | `terminal_time - arrival_time` | Completed requests only |
| Queueing `queueing_s` | Sum of every queue episode, in and out of preemption | Requests that ever queued |
| Prefill `prefill_s` | Sum of prefill step durations over all attempts | Requests that were prefilled |
| Decode `decode_s` | Sum of the per-sequence share of every decode iteration | Requests that decoded |
| Energy `energy_j` | Share of active device energy while the request occupied the device | Every admitted request |
| Preemptions | Count of suspensions | Every request |
| Cached tokens | Tokens held in the cache at the last step | Every admitted request |
| Allocated blocks | Blocks held at the last step | Every admitted request |

A decode iteration's duration is split equally among the sequences that took part in it, because
the iteration is one step on one device and no finer attribution exists in the model. The share is
used for `decode_s`, for energy and for the per-request totals.

A metric that was never measured is absent, not zero. `requests.csv` writes an empty cell or `NaN`
where a phase never happened, and the console prints `-` for a percentile with no observation.
The distinction matters: a refused request has no TTFT, and reporting `0` would place it at the
front of a sorted distribution.

## Outcomes

A request ends in one of four states: `COMPLETED`, `REJECTED`, `TIMED_OUT` or `CANCELLED`. The
reason next to it is one of:

| Reason | State | Cause |
|---|---|---|
| `success` | `COMPLETED` | Every requested token was generated |
| `admission_queue_limit` | `REJECTED` | The queue-limit policy refused the request |
| `admission_no_cache_capacity` | `REJECTED` | The cache-capacity policy, or routing with no feasible replica, refused |
| `admission_deadline_infeasible` | `REJECTED` | The predicted-deadline policy refused |
| `no_replica_for_model` | `REJECTED` | No replica serves the requested model |
| `prompt_exceeds_model_context` | `REJECTED` | The prompt is longer than `model.maxContextTokens` |
| `prompt_exceeds_cache_capacity` | `REJECTED` | The prompt alone cannot fit any cache pool |
| `client_cancelled` | `CANCELLED` | The client gave up after `cancellationDelaySeconds` |
| `deadline_missed` | `TIMED_OUT` | The end-to-end deadline passed and `abortOnDeadlineMiss` is on |
| `simulation_ended` | `TIMED_OUT` | The request was still running when the run reached its duration |
| `context_limit_reached` | `COMPLETED` | Generation stopped at the model or cache context limit before the requested tokens |
| `preempted_not_resumed` | `REJECTED` | The request was preempted and could not be readmitted before the end |

`context_limit_reached` is a completed request with fewer tokens than requested. It counts as
completed, and its end-to-end latency is measured, but it is visible in the outcome histogram, so
a run that "completes" everything by truncating output cannot hide behind the success ratio.

The outcome histogram in `summary.csv` is written as `reason:count` pairs, sorted, for a stable
artifact.

## Service levels and goodput

Three objectives can be stated per request: TTFT, end-to-end and TPOT. A target of `0` means "not
stated", and a request that stated no objective is not counted as met or missed.

| Quantity | Definition |
|---|---|
| `requests_with_objective` | Offered requests that stated at least one target |
| `requests_within_objective` | Of those, the ones that met every target they stated |
| `goodput` | `requests_within_objective / requests_offered` |
| `success_ratio` | `requests_completed / requests_offered` |
| Attainment per class | `met / applicable` for each objective |

Goodput uses **offered** requests as its denominator, so a policy that meets every target by
refusing the hard requests cannot raise it. Attainment per class uses **applicable** requests as
its denominator, so it answers a different question: among the requests that cared, how many were
satisfied. Both are reported because a single ratio can be optimized without improving the
deployment.

A workload that states no targets has a goodput of zero by definition, since the numerator is
empty. The console prints attainment as `-` when a class stated no objective, rather than 100
percent.

TPOT is checked only for requests that produced at least two tokens; a single-token response has
no per-token interval to check.

## Summaries

Every duration metric is summarised with count, mean, standard deviation, p50, p90, p95, p99 and
maximum. Percentiles use linear interpolation between the two closest ranks, which is the common
numeric definition (the NumPy default), so a reader who loads the CSV into Pandas gets the same
number.

Two summaries of the same metric are reported:

- **Pooled**: every request row of every repetition in one distribution. This answers "what did
  the deployment do to a request" and is the one a capacity planner reads.
- **Across repetitions**: one value per repetition (its mean), summarised over repetitions. This
  answers "how much does the answer move when the seed changes" and prints a standard error. With
  one repetition the spread is zero, because a single observation contains no information about
  spread.

Percentiles are not combinable, so the pooled summary is built from the raw values, not by
averaging per-repetition percentiles.

## Throughput and device aggregates

| Column | Formula |
|---|---|
| `tokens_per_second` | Generated tokens over the simulated duration |
| `requests_per_second` | Completed requests over the simulated duration |
| `device_utilisation` | Mean over replicas of active seconds over duration, capped at 1 |
| `mean_batch` | Mean decode batch size over iterations |
| `peak_batch` | Largest decode batch |
| `decode_iterations` | Number of decode steps executed |
| `prefill_steps` | Number of prefill chunks executed |
| `preemptions` | Sequences suspended and requeued |
| `cache_refusals` | Block reservations refused |
| `stalled_steps` | Moments with no schedulable step |
| `peak_cache_occupancy` | Peak used blocks over total blocks, in `[0, 1]` |
| `sampler_bindings` | Samplers rebuilt for the repetition, one per declared distribution |
| `energy_j`, `energy_wh` | Device energy over the repetition |
| `cost_calibration` | Whether the cost model is calibrated |
| `outcomes` | Outcome histogram, `reason:count` pairs |

## The console report

The console prints, in order:

1. a header naming the build, the experiment, the model, the device, the replica count, the cost
   model, the overall provenance, the policies and the workload;
2. any warning attached to the cost model or the run;
3. one row per repetition: offered, completed, success, four latency percentiles, goodput,
   tokens per second, energy;
4. the pooled table and the per-repetition mean with its standard error;
5. service-level attainment per class;
6. one line per replica with its counters;
7. the artifact directory.

The header is deliberately verbose about provenance: the first thing a reader should see is whether
the numbers are measured or estimated.

## `requests.csv` columns

Fifty-three columns, in this order:

```text
experiment, seed, repetition, cost_model, cost_provenance, cost_calibrated,
model_provenance, device_provenance,
request_id, model_id, replica_id, tenant_id, service_class, priority, session_id,
arrival_time_s, admitted_time_s, queue_entry_time_s, prefill_start_time_s,
first_token_time_s, last_token_time_s, terminal_time_s, delivery_time_s,
final_state, outcome_reason,
input_tokens, output_tokens_requested, output_tokens_generated, predicted_output_tokens,
cached_tokens, allocated_blocks,
ttft_s, tpot_s, mean_itl_s, max_itl_s, e2e_s, queueing_s, prefill_s, decode_s,
uplink_s, downlink_s,
energy_j, energy_wh, preemptions,
ttft_target_s, e2e_target_s, tpot_target_s, cancellation_time_s,
ttft_met, e2e_met, tpot_met, all_slo_met, decode_iterations_expected
```

Timestamps that never happened are empty. A time of `0` in a timestamp column is a real instant at
the start of the run, not a sentinel; the sentinel is an empty cell.

`uplink_s` and `downlink_s` are the time the prompt and the response spent on the access link, and
`delivery_time_s` is the instant the client held the whole response. A deployment that names no
bearer reports `0` for both durations and a delivery equal to the terminal time: the row says the
link cost nothing, which is what assuming no link means. The two figures are reported separately
from `ttft_s` and `e2e_s` because those already contain the uplink, the request being timed from
the instant the client released it; `e2e_s` stops at the last token the device produced, so the
trip home is the part of a client's wait that the latency columns leave out.

A disaggregated deployment adds no column either. The key-value hand-off is charged onto the fabric
between the prefill step and the first token, so its delay sits inside `ttft_s` and `e2e_s` rather
than in a column of its own; a deployment that names no `deployment.fabric` hands off instantly and
the row is identical to a colocated one.

`requests.jsonl` holds the same rows as JSON objects, one per line. Text columns are quoted; the
numbers keep the precision of the CSV.

`decode_iterations_expected` is the number of decode steps the request would need if it received
the full device alone: `ceil(generated / 1)`, recorded so a reader can compare the share the
request got under batching with the steps it actually caused.

## `samples.csv` columns

```text
time_s, replica_id, running_sequences, waiting_requests, used_blocks, total_blocks,
reserved_tokens, cache_occupancy, energy_j, executing
```

One row per replica per `metrics.samplingIntervalSeconds`. `energy_j` is cumulative. `executing` is
`true` when at least one sequence was running during the interval, which makes a duty cycle
directly computable from the column.

## `slo.csv` columns

```text
experiment, repetition, service_class, objective, offered, completed, applicable, met, attainment
```

One row per class, repetition and objective (`ttft`, `e2e`, `tpot`, `all`). `applicable` counts the
requests of that class that stated the objective, and `attainment` is `met / applicable`, or `0`
when nothing was applicable. Use `slo.csv` when the question is per class; use `summary.csv` when
it is about the deployment as a whole.

## `config.txt`

The resolved scenario, followed by:

- the provenance of the weakest input;
- the cost model, its provenance, its calibration flag and its calibration source;
- every warning the run produced;
- the trace description, as a `# Trace:` line, when the workload replays a recorded file;
- the pooled count, mean and p99 of each metric.

The file is plain text, one fact per line, so a result can be traced back to the inputs without a
second file.

## Invariants

`RunResult` checks these facts when it is built, and the invariant tests re-derive them from the
raw rows:

1. every arrived request is terminal exactly once;
2. completed requests do not outnumber terminal ones;
3. good requests do not outnumber requests that stated an objective;
4. device energy equals the sum over replicas and is at least the idle floor;
5. utilisation lies in `[0, 1]`;
6. pooled statistics equal the corresponding sums over the request rows;
7. no reservation exceeds the pool.

A run that breaks one of them aborts the experiment instead of producing a partial result. A result
whose rows and aggregates disagree is not a measurement, and keeping the other repetitions would
present a broken run as a sampled one.
