# Roadmap

As of 2026-09-16. Updated when direction changes, reviewed at least once per release cycle.

## Where the project is

**`2.0.0-RC1` is on Maven Central** — the first publication of the 2.x family: annotation processor,
runtime library, query runtime and a BOM, released together under one version. It is a release
candidate: the API is frozen, and the compatibility gate now compares every build against it. The
1.x Maven plugin that preceded it, which parsed entity sources
with JavaParser, was retired and **removed from the repository on 2026-08-30**; it survives in git
history and in its own tags.

## Where it is going

A **2.0 reboot** replaces the source-parsing Maven plugin with a **JSR-269 annotation processor**
plus a small owned runtime library. Every part is built and the public API froze on 2026-09-06;
what stands between here and a first release is packaging and release work, not core function:

- **Runtime library** — typed references (`EntityRef` / `PropertyRef` / `JoinRef`) that generated
  metamodels compile against. No Spring SQL type appears in any public signature, and the few
  admitted framework types are pinned by an architecture test. *Released in `2.0.0-RC1`.*
- **Annotation processor** — build-tool-neutral generation (Maven, Gradle, IDE builds),
  incremental-compilation aware. *Released in `2.0.0-RC1`; generates the frozen shape.*
- **Fluent query surface** — typed, composable `SELECT` construction over Spring Data R2DBC,
  including joins, driven by the generated metamodel. Paging covers `Page`, the count-free `Slice`
  and a streaming window; keyset (cursor) scrolling is deliberately not built, and the README's
  query recipes carry the answer that serves it meanwhile. *Released in `2.0.0-RC1`
  (`entity-metamodel-runtime-r2dbc`); executes against a real PostgreSQL in its integration suite.*
- **BOM** — one aligned version for the whole family. *Released in `2.0.0-RC1`.*

A relationship-annotation module (`@References`) is deliberately post-2.0.0: the aligned version
policy makes a new artifact and its BOM row an additive minor, so it waits for usage evidence
rather than holding the release.

**Binding external names** — accepting filter and sort terms that arrive as text, from a web
request or from a machine caller — is post-2.0.0 and waits on the same evidence. Two facts belong
beside that decision rather than behind it. The doors that already take text resolve *any*
persisted property of the entity, so an application forwarding a filter or a sort term it did not
build is the thing deciding what may be filtered and sorted on; the library does not narrow it.
And the library bounds no request's cost — it caps no `IN` list and no page size; it refuses
only a condition nested past what it could render at all. An allow-list and a budget are what a
binding layer would add, and the budget is the half a caller with no web layer in front of it needs
most. That half is warranted by the doors that ship
today, so it is not gated on demand the way the module is.

**What stands between `2.0.0-RC1` and `2.0.0`:** a soak on the candidate, and the post-release checklist.
The first compatibility-gate run against the published baseline has been reviewed, and the marker
contract — `@RawSql` on the raw doors, both markers' retention — is held by the API ledger as well as
by tests. The candidate was published whole — it generates a
metamodel, builds a query and executes it — because Central is permanent and a milestone of parts
nobody can use would have been permanent too. The 1.x plugin has been removed. It emitted into Spring's own packages, which is the defect 2.x
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
