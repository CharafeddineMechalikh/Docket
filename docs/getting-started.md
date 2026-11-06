---
description: "Install, build, and run your first Docket simulation, read the console report and the artifacts, run a sweep, and use the simulator from your own code."
---
# Getting started

This guide takes you from a clean machine to a finished Docket run, then explains what the run
produced. It assumes no prior knowledge of the simulator, but it does assume basic familiarity with
Java and the command line. Docket needs **no GPU**: the latency, memory and energy numbers come from
analytical models, so a laptop runs a full experiment in seconds.

## What you need

| Requirement | Version | Notes |
| --- | --- | --- |
| Java Development Kit | 11 or newer | The project builds with a modern JDK; the engine targets Java 8. |
| Maven | 3.6 or newer | Used for compilation, tests, packaging, and Javadoc. |
| Git | Any recent version | Needed only to clone the repository. |
| A GPU | Not required | The PureEdgeSim engine is resolved as a normal Maven dependency. |

Check your installation:

```bash
java -version
mvn -version
```

If Maven prints a Java version different from `java -version`, set `JAVA_HOME` to the JDK you want
Maven to use.

## Build

```bash
git clone https://github.com/CharafeddineMechalikh/Docket.git
cd Docket
mvn clean package
```

This produces `target/docket-1.0.1.jar` and fills `target/lib/` with the runtime dependencies, so the
jar is runnable on its own. Build the project once before running anything; the examples and the
sweeps both call into the compiled classes.

## Run your first scenario

A scenario is one properties file that names a model, a device fleet, a workload, and the policies.
The committed scenarios live in `scenarios/`. Run the simplest one:

```bash
java -jar target/docket-1.0.1.jar scenarios/single-gpu-poisson.properties
```

Running from loose classes works too, which is handy while you edit:

```bash
java -cp "target/classes:target/lib/*" com.mechalikh.docket.cli.Main \
    scenarios/single-gpu-poisson.properties
```

The console report starts with what ran and how far its numbers can be trusted, then a per-request
latency summary, batching and cache statistics, energy, and the service-level attainment. Every figure
carries a provenance label, so you can tell a synthetic estimate from a measured one at a glance.

## Read the output

Alongside the console report, a run writes artifacts into the output directory (the scenario's
`experiment.outputDir`):

- `requests.csv` and `requests.jsonl` hold one row per request: arrival, admission, prefill and
  decode times, time-to-first-token, inter-token latency, end-to-end delay, energy, preemptions, and
  the SLO columns. Timestamps that never happened are empty, not zero.
- `samples.csv` holds the time series of device state (batch size, allocated blocks, power) sampled
  through the run.
- The console report is the human summary; the CSV and JSONL are what you plot.

The exact meaning of every column is in [Metrics and artifacts](metrics.md).

## Run a sweep

To average over randomness, ask for repetitions with a fixed seed:

```bash
java -jar target/docket-1.0.1.jar -r 3 scenarios/loaded-7b-memory-pressure.properties
```

The committed sweeps live in `scripts/run_sweeps.sh` and reproduce every number the README and the
paper quote. See [Reproducing results](reproducing.md).

## Change one knob without editing the file

Any scenario property can be overridden on the command line, which is how you compare configurations
without maintaining many files:

```bash
java -jar target/docket-1.0.1.jar \
    -D replica.maxBatchSize=8 scenarios/loaded-7b-memory-pressure.properties
```

The loader is strict: a key that is set but never read prints a warning, because a silently ignored
setting is how a result ends up describing something other than what you wrote.

## The command line in full

```text
usage: docket [options] <scenario.properties>

  -c, --scenario FILE     the scenario to run
  -o, --output DIR        override experiment.outputDir
  -s, --seed N            override experiment.seed
  -r, --repetitions N     override experiment.repetitions
  -D key=value            set or override any scenario property
  -q, --quiet             write the artifacts without the console report
  -h, --help              show this message
  --version               show the version
```

Exit codes are stable, so scripts can branch on them:

| Code | Meaning |
|---|---|
| `0` | The experiment finished and its artifacts were written |
| `2` | Usage error: bad flag, missing file, unreadable path |
| `3` | The scenario does not load: missing key, bad value, unreadable trace |
| `4` | The run failed or an artifact could not be written |

## Where to go next

- To understand what each setting means, read [Configuration](configuration.md).
- To reason about a latency or energy number, read [Cost model](cost-model.md).
- To compare scheduling or admission strategies, or to write your own policy, read
  [Policies](policies.md) and the [worked examples](../examples/README.md).
- To use Docket from your own code, depend on the built jar and call the classes under
  `com.mechalikh.docket`; the method-level API is on
  [Javadoc](https://javadoc.io/doc/io.github.charafeddinemechalikh/docket), and the class map is in
  [API reference](api-reference.md).
