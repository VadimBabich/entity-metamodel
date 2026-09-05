# Roadmap

As of 2026-09-05. Updated when direction changes, reviewed at least once per release cycle.

## Where the project is

Nothing is shipping. The 2.x family — annotation processor, runtime library, query runtime and a
BOM — is built and unpublished. The 1.x Maven plugin that preceded it, which parsed entity sources
with JavaParser, was retired and **removed from the repository on 2026-08-30**; it survives in git
history and in its own tags.

## Where it is going

A **2.0 reboot** replaces the source-parsing Maven plugin with a **JSR-269 annotation processor**
plus a small owned runtime library. Every part is built; what stands between here and a first
release is freeze and packaging work, not core function:

- **Runtime library** — typed references (`EntityRef` / `PropertyRef` / `JoinRef`) that generated
  metamodels compile against, with no framework types in the public API surface. *Built.*
- **Annotation processor** — build-tool-neutral generation (Maven, Gradle, IDE builds),
  incremental-compilation aware. *Built, generating the frozen shape.*
- **Fluent query surface** — typed, composable `SELECT` construction over Spring Data R2DBC,
  including joins, driven by the generated metamodel. Paging covers `Page` and the count-free
  `Slice`; keyset (cursor) scrolling is deliberately not built, and the README's query recipes carry
  the answer that serves it meanwhile. *Built (`entity-metamodel-runtime-r2dbc`), executing against
  a real PostgreSQL in its integration suite.*
- **BOM** — one aligned version for the whole family. *Built.*

Nothing is on Maven Central yet, deliberately: the first release there will be a version that runs
end to end — generate a metamodel, build a query, execute it — not a milestone of parts nobody can
use. The 1.x plugin has been removed. It emitted into Spring's own packages, which is the defect 2.x
exists to remove; the surviving shape is pinned by a committed corpus of golden files so it cannot
drift.

## What guides the order

Correctness gates before features: the generated-shape freeze and the incremental-compilation
decision precede any 2.0 publication, and API/SPI compatibility gating precedes **2.0.0 itself**.
Milestones are deliberately outside that guarantee — that is what the `-M` qualifier buys, and why
the processor reaches output parity against the golden corpus before GA rather than before M1.

## Influencing the roadmap

File a **use case** issue (template provided). Real usage reports directly reprioritize this
list — that is not a platitude; the fluent-query design was derived from measured usage in real
codebases.
