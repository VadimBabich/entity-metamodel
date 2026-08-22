# Roadmap

As of 2026-08-21. Updated when direction changes, reviewed at least once per release cycle.

## Where the project is

The shipping artifact is the 1.x Maven plugin: it parses Spring Data Relational entity sources
and generates static metamodel classes for type-safe query construction with Spring Data R2DBC.
Version 1.0.0 is abandoned; the 1.1.x line is current.

## Where it is going

A **2.0 reboot** replaces the source-parsing Maven plugin with a **JSR-269 annotation processor**
plus a small owned runtime library. The model vocabulary and the runtime reference types are built;
the processor and query surface are next:

- **Runtime library** — typed references (`EntityRef` / `PropertyRef` / `JoinRef`) that generated
  metamodels compile against, with no framework types in the public API surface.
- **Annotation processor** — build-tool-neutral generation (Maven, Gradle, IDE builds),
  incremental-compilation aware.
- **Fluent query surface** — typed, composable `SELECT` construction over Spring Data R2DBC,
  including joins, driven by the generated metamodel.
- **BOM** — one aligned version for the whole family.

Nothing is published yet, deliberately: the first release to Maven Central will be a version that
generates code, not a milestone of parts nobody can use. The 1.x plugin is maintained through the
transition and retired in stages after the processor reaches output parity — the generated code of
both generations is held byte-identical by a committed golden corpus until then.

## What guides the order

Correctness gates before features: the generated-shape freeze and the incremental-compilation
decision precede any 2.0 publication, and API/SPI compatibility gating precedes **2.0.0 itself**.
Milestones are deliberately outside that guarantee — that is what the `-M` qualifier buys, and why
the processor reaches output parity against the golden corpus before GA rather than before M1.

## Influencing the roadmap

File a **use case** issue (template provided). Real usage reports directly reprioritize this
list — that is not a platitude; the fluent-query design was derived from measured usage in real
codebases.
