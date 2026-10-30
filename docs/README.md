# GenAIInferenceSim documentation

The [README](../README.md) is the tour. These pages are the reference: each one covers a
mechanism in full, states the rules the code enforces, and gives the numbers or commands that
check the claim.

| Page | What it answers | Read it when |
|---|---|---|
| [Architecture](architecture.md) | How the engine is put together, how a request moves, why a run is deterministic | You want to change the engine or understand a result |
| [Configuration](configuration.md) | Every setting, its default, its validation rule and its unit | You are writing or debugging a scenario |
| [Cost model](cost-model.md) | The roofline formulas, the binding term, the power integral, the calibration contract | You are reasoning about a latency or an energy number |
| [Memory and caching](memory.md) | How device memory becomes a KV pool, how blocks are reserved, what preemption does | A run shows cache refusals, preemption or a batch ceiling |
| [Policies](policies.md) | Admission, routing and scheduling rules, including the oracle modes | You are comparing scheduling or admission strategies |
| [Metrics and artifacts](metrics.md) | Definitions, SLO accounting, CSV schemas, console layout, invariants | You are reading or plotting a result |
| [Trace replay](traces.md) | The trace format, time scaling, and how a public trace maps onto it | You want to replay real traffic |
| [Reproducing results](reproducing.md) | Build, test, sweeps, determinism, environment | You are checking a published number |

Three reading paths:

- **Evaluator.** README, then [cost model](cost-model.md) and [memory](memory.md), then
  [provenance](../README.md#provenance-and-limits). That is enough to decide whether a number is
  meaningful.
- **Scenario author.** README, then [configuration](configuration.md), then [metrics](metrics.md).
- **Contributor.** [Architecture](architecture.md), then [policies](policies.md), then the tests
  under `src/test/java/com/mechalikh/genaism/`.

Every page keeps the same rule as the code: a synthetic input is labelled synthetic, a measured
input is labelled measured, and nothing is presented as calibrated until it is.
