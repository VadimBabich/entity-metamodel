# Roadmap

As of 2026-09-06. Updated when direction changes, reviewed at least once per release cycle.

## Where the project is

Nothing is shipping. The 2.x family — annotation processor, runtime library, query runtime and a
BOM — is built and unpublished. The 1.x Maven plugin that preceded it, which parsed entity sources
with JavaParser, was retired and **removed from the repository on 2026-08-30**; it survives in git
history and in its own tags.

## Where it is going

A **2.0 reboot** replaces the source-parsing Maven plugin with a **JSR-269 annotation processor**
plus a small owned runtime library. Every part is built and the public API froze on 2026-09-06;
what stands between here and a first release is packaging and release work, not core function:

- **Runtime library** — typed references (`EntityRef` / `PropertyRef` / `JoinRef`) that generated
  metamodels compile against. No Spring SQL type appears in any public signature, and the few
  admitted framework types are pinned by an architecture test. *Built, frozen.*
- **Annotation processor** — build-tool-neutral generation (Maven, Gradle, IDE builds),
  incremental-compilation aware. *Built, generating the frozen shape.*
- **Fluent query surface** — typed, composable `SELECT` construction over Spring Data R2DBC,
  including joins, driven by the generated metamodel. Paging covers `Page`, the count-free `Slice`
  and a streaming window; keyset (cursor) scrolling is deliberately not built, and the README's
  query recipes carry the answer that serves it meanwhile. *Built
  (`entity-metamodel-runtime-r2dbc`), frozen, executing against a real PostgreSQL in its
  integration suite.*
- **BOM** — one aligned version for the whole family. *Built.*

A relationship-annotation module (`@References`) is deliberately post-2.0.0: the aligned version
policy makes a new artifact and its BOM row an additive minor, so it waits for usage evidence
rather than holding the release.

Nothing is on Maven Central yet, deliberately: the first release there will be a version that runs
end to end — generate a metamodel, build a query, execute it — not a milestone of parts nobody can
use. The 1.x plugin has been removed. It emitted into Spring's own packages, which is the defect 2.x
exists to remove; the surviving shape is pinned by a committed corpus of golden files so it cannot
drift.

## What guides the order

Correctness gates before features: the generated-shape freeze and the incremental-compilation
decision preceded any 2.0 publication, and API/SPI compatibility gating preceded **2.0.0 itself** —
it now runs in every build, and the API froze behind it on 2026-09-06.
Milestones are deliberately outside that guarantee — that is what the `-M` qualifier buys, and why
the processor reaches output parity against the golden corpus before GA rather than before M1.

## Influencing the roadmap

File a **use case** issue (template provided). Real usage reports directly reprioritize this
list — that is not a platitude; the fluent-query design was derived from measured usage in real
codebases.
