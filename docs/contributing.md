---
description: "How to build, test, and extend Docket: the Maven layout, the test layers and the accounting invariant, the worked-example harness, external policy loading, documentation conventions, and the GitHub Pages build rules."
---
# Contributing

Docket is GPL-licensed and every source file carries the GPL header. This page covers how to build
and test the project, how to add a scenario or a policy, and the conventions the repository follows,
including the ones that keep the documentation site building.

## Build and test

```bash
mvn clean package
```

This compiles the engine layer, runs the tests, and produces `target/docket-1.0.1.jar` with its
runtime dependencies under `target/lib/`. To run only the tests:

```bash
mvn test
```

A full run takes about twenty seconds on the development machine. Do not launch two Maven builds at
once; the tests write artifacts into `target/` and overlapping builds corrupt them.

## The test layers

The suite is 242 tests, split by layer:

| Layer | Count | What it covers |
| --- | --- | --- |
| Unit | 147 | Models, cost, memory, policies, config, state transitions in isolation. |
| Integration | 19 | End-to-end scenarios through the engine. |
| Invariant | 2 | The accounting invariant, checked on every run. |
| Regression | 3 | Numbers that must not drift between releases. |
| Command line | 16 | Flag parsing, overrides, and exit codes. |
| Examples | 55 | The worked examples, each asserting its measured result. |

The invariant test is the load-bearing one: every finished run reconciles its per-request totals
against the aggregated report, so a change that silently breaks accounting fails the suite rather than
shifting a published number.

## The worked examples

The examples under `examples/` are compiled into the test build through the `build-helper-maven-plugin`
and are exercised by `mvn test`, but they never enter the jar. Each example folder holds its scenario,
its sources, a `run.sh`, and a committed `results/` capture (config, console, SLO, summary). When you
add an example, commit its measured result so the README table and the paper can quote it from the
artifact rather than from a re-run.

## Adding a policy outside the engine

A custom admission, routing, or scheduling rule does not require editing Docket. Compile your class
against the jar, give it a public no-argument constructor, put it on the runtime classpath, and name
it in the scenario. The class-loading hook instantiates it by name. The three interfaces and their
shipped implementations are listed in [API reference](api-reference.md); [Policies](policies.md)
explains the decision each layer makes.

## Documentation conventions

The `docs/` folder is the manual, published to GitHub Pages. Two rules are not obvious and both break
the site silently if forgotten:

- **Every `docs/*.md` needs YAML front matter.** Jekyll only turns a Markdown file into a page when it
  begins with a `---` block; a file without one is copied as a static file and never appears on the
  site. The manual home (`docs/README.md`) additionally sets `permalink: /docs/`.
- **Never put a Liquid open delimiter in any tracked Markdown.** Pages runs Liquid over every file
  before Markdown, so two adjacent left braces, or a left brace followed by a percent sign, open a
  template tag; a BibTeX capitalization guard that starts with two braces is therefore a fatal build
  error, not a rendering quirk. Because the site builds the whole repository, this applies to every
  README, not just the docs.

The left-hand navigation and the per-page table of contents are generated on the client by
`_includes/head-custom.html`, which reads the `##` and `###` headings of the current page. Add a
section with a normal Markdown heading and it appears in the contents automatically; to add a whole
page, list it in the `pages` array in that file.

## Releasing

Releases bump the version in every file that carries it, tag the commit, and publish the artifacts.
The manuscript and the `paper/` directory are gitignored and never travel with a push or a release.
See [Reproducing results](reproducing.md) for how a published number is regenerated from the
committed artifacts.
