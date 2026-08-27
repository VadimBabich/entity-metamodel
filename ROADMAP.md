# Roadmap

As of 2026-08-27. Updated when direction changes, reviewed at least once per release cycle.

## Where the project is

The shipping artifact is the 1.x Maven plugin: it parses Spring Data Relational entity sources
and generates static metamodel classes for type-safe query construction with Spring Data R2DBC.
Version 1.0.0 is abandoned; the 1.1.x line is current.

## Where it is going

A **2.0 reboot** replaces the source-parsing Maven plugin with a **JSR-269 annotation processor**
plus a small owned runtime library. Every part is built; what stands between here and a first
release is freeze and packaging work, not core function:

- **Runtime library** — typed references (`EntityRef` / `PropertyRef` / `JoinRef`) that generated
  metamodels compile against, with no framework types in the public API surface. *Built.*
- **Annotation processor** — build-tool-neutral generation (Maven, Gradle, IDE builds),
  incremental-compilation aware. *Built, generating the frozen shape.*
- **Fluent query surface** — typed, composable `SELECT` construction over Spring Data R2DBC,
  including joins, driven by the generated metamodel. *Built (`entity-metamodel-runtime-r2dbc`),
  executing against a real PostgreSQL in its integration suite.*
- **BOM** — one aligned version for the whole family. *Built.*

Nothing is published yet, deliberately: the first release to Maven Central will be a version that
runs end to end — generate a metamodel, build a query, execute it — not a milestone of parts
nobody can use. The 1.x plugin is maintained
through the transition and retired in stages. The two generations emit different shapes — 1.x still
emits into Spring's own packages, which is the defect 2.x removes — and each shape is pinned by its
own committed corpus of golden files, so neither can drift while the transition runs.

## What guides the order

Correctness gates before features: the generated-shape freeze and the incremental-compilation
decision precede any 2.0 publication, and API/SPI compatibility gating precedes **2.0.0 itself**.
Milestones are deliberately outside that guarantee — that is what the `-M` qualifier buys, and why
the processor reaches output parity against the golden corpus before GA rather than before M1.

## Influencing the roadmap

File a **use case** issue (template provided). Real usage reports directly reprioritize this
list — that is not a platitude; the fluent-query design was derived from measured usage in real
codebases.
