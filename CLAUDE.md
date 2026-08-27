# Agent Instructions

Committed and public. Never add private-material paths, production-system identifiers, or
quotes from third-party source held under licence. Maintainer-local rules — including the map
of private design records — live in the uncommitted `CLAUDE.local.md`.

**This repository:** the shipping 1.x `jpa-metadata-maven-plugin` (JavaParser-based Maven Mojo
generating entity metamodels for Spring Data R2DBC), plus the paper design program for its v2
reboot as a JSR-269 annotation processor with an owned runtime library. The v2 design is
recorded privately; read it before writing any v2 code.

## Hard rails — violations are one-way doors

1. **Private working material never enters git.** It includes third-party source held under
   licence. Never weaken the `.gitignore` entries that exclude it, and never quote its
   contents — or cite its paths and filenames — from committed files. CI enforces the
   backstop; `.gitignore` is the primary guard.
2. **Never `git add .` or `git add -A`.** Stage explicit paths only.
3. **Push only via the SSH alias `github.com-vadimbabich`** (account isolation). Never point
   `origin` at a plain `github.com` URL.
4. **Golden corpus** (`jpa-metadata-maven-plugin/src/it/simple-consumer/expected/**`): any
   change — including formatting and comments — requires an approving decision record that
   already exists and is **named in the commit message**, since those records are not
   committed and cannot ride the same diff. Generated output must stay byte-deterministic: no
   dates, no environment-dependent content. Procedure:
   `docs/runbooks/golden-corpus-update.md`.
5. **Quantities derived from private evidence keep their provenance marker** (`[review-time]` =
   provisional, or attested). Never restate them as verified facts, and never reproduce the
   evidence itself.
6. **Never commit credentials, tokens or keys.** CI scans full history with gitleaks, but it
   only *detects*: a secret in a pushed commit is compromised and must be **rotated** —
   rewriting history does not undo it.
7. **No email address enters this repository unless it is on the allowlist.** That covers
   commit authorship, commit messages, *and* file content, including POM `<developers>`. Commit as
   `<id>+<user>@users.noreply.github.com`; never introduce a personal address, and never
   invent a contact address to satisfy a validator — this project routes contact through
   GitHub security advisories, and a published POM cannot be edited afterwards. If an address
   genuinely must ship, it is a role or alias address and it goes into
   `.github/allowed-emails.txt` in the same commit, which is the record of that decision.
   Enforced by `.github/scripts/check-emails.sh` — the Guards workflow runs it over each
   push's new commits, and `git config core.hooksPath .githooks` runs it pre-commit (enable it
   once per clone; the hook is the convenience, CI is the control).

## Documents

`docs/` is committed and must be publishable as-is. Everything else — the design paper,
decision records, plans and investigations — is private and gitignored. Put new files on the
correct side, and keep committed files free of references to the private side.

- `docs/runbooks/golden-corpus-update.md` — the corpus procedure. Follow it rather than
  re-deriving it.
- `docs/runbooks/front-door-docs.md` — keeping `README.md`, `ROADMAP.md` and `CHANGELOG.md` telling
  one story. Run it whenever a capability, state word or floor changes; never update one of the
  three alone.
- `docs/reactive-code-style.md` — the reactive rule set (hand-written runtime, generated output,
  tests). Read it before writing or emitting a single reactive line; its §1 and §8 are contractual.
- `docs/shell-code-style.md` — the shell rule set (guards, hooks, workflow `run:` blocks). Read §1
  and §2 before touching any script: they record failure modes that were shipped and caught here,
  not general advice.
- `CLAUDE.local.md` (uncommitted) — where the private records live and what they decide.

## Current state (2026-08-21)

- Shipping: the 1.x Mojo at `1.1.0-SNAPSHOT`; `master` is pushed to `origin`. Its code lives in
  `jpa-metadata-maven-plugin/src/main/java/io/github/vadimbabich/metadata/`: `parser/`
  (JavaParser) → `graph/` → `generator/` (JavaPoet), behind `api/`.
- `entity-metamodel-core` and `entity-metamodel-runtime` exist at `2.0.0-SNAPSHOT`; the
  annotation processor, the r2dbc execution module and the fluent query API do not.
- **The v2 decisions are proposals, not settled.** Several would revise things the design paper
  states as decided. Do not pre-apply a proposal to committed artifacts. Concretely: `pom.xml`
  enforces Maven `[3.9,)` while a pending decision proposes lowering it to 3.6.3 — leave the
  POM alone until that is resolved.
- Publication is gated: nothing in the family has been released, and the release workflow
  refuses by design (see below).

## Branching and releases

- Work on `master` through short-lived `feature/YYYY.MM_short-desc` branches. Commit or push
  only when asked.
- **No long-lived `2.x` or `develop` branch.** v2 ships as milestones cut from `master`
  (`2.0.0-M1` → `-RC1` → `2.0.0`). A version branch
  is unworkable here: 1.x's remaining releases depend on v2 artifacts — runtime/core publishes
  at `2.0.0-M1` before the 1.x release that consumes it — and the golden corpus is the shared
  1.x⇄v2 parity contract.
- `release/2.0.x` or `1.1.x` branches on demand only, cut from a tag, then deleted.
- Releases are dispatch-triggered (`.github/workflows/release.yml`); milestones and RCs take the
  same path. Tag as `v<version>`, matching `v1.1.0` — the unprefixed `1.0.0` is a known
  inconsistency; do not add more.
- **Releases run only through the workflow; never run `release:prepare` by hand.** It sets versions
  ephemerally from the dispatch inputs and deploys one line per run, dry-run by default. A manual
  cut would publish an unresolvable parent, since every module parents to `2.0.0-SNAPSHOT`.
- The dispatch is gated on `master`, so a dry run must be triggered there, not from a branch.
- **The repository is `entity-metamodel`** (renamed 2026-08-21). Never create a repository under
  the old name — that silently kills GitHub's redirects. Published coordinates are unchanged: the
  retiring 1.x artifact keeps `jpa-metadata-maven-plugin`, which is also its module directory.

## Code style

Optimize for scanning, not compactness.

- Explicit control flow over clever compression: no chained ternaries, no nested lambdas, no
  long `Optional` chains, no conditional embedded in a return expression. Stream pipelines are
  fine while they read as one idea — `stream().filter().map().collect()` in `parser/` is the
  house shape — but become a loop once a chain carries branching.
- Blank lines between logical steps. One responsibility per method, no boolean flag parameters.
- Names carry domain meaning (`normalizedKey`, `persistedEntity`), never `data`, `value`, `obj`,
  `result`. Never return `null`; prefer immutable collections and `List.of()`/`Map.of()`.
- Constructor injection, never field injection — except Mojo `@Parameter` fields, which Maven
  injects by design.
- `src/main/java` declares explicit types today (no `var`). Match the file you are editing
  rather than introducing a second style.

**Reactive code** — rules in `docs/reactive-code-style.md`; **read it before writing or emitting
any reactive line.** Costliest mistakes it prevents: blocking, scheduling or embedding
`timeout`/`retry`/`cache` in library or generated code · eager `switchIfEmpty` fallbacks ·
re-subscribing a named publisher · unbounded `flatMap` · asserting with `block()` over `StepVerifier`.

**Shell scripts** — rules in `docs/shell-code-style.md`; **read §1–§2 before touching any script
or workflow `run:` block.** Load-bearing there: `set -euo pipefail` and **never `-E`** · bash 3.2
only · a pipeline reports only its last stage · fail closed on empty input · `shellcheck -S style`
plus tests that assert stderr, not just exit codes.

## Comments — every file type

Treat comments as debt that must earn its place. **Assume a comment is unnecessary until it
proves otherwise: if deleting it costs no understanding, delete it.** This applies to Java,
shell, workflow YAML, POM XML and config alike — the language changes the syntax, not the test.

**Keep a comment only when it explains one of these:** a non-obvious rule or constraint, a
workaround for external behaviour, a decision and its rationale, deliberately surprising code, or
a concurrency, security, performance or compatibility concern. Prefer one or two sentences.

**Delete on sight:**

- Anything restating the code — `# increment counter`, `<!-- set the version -->`, a JavaDoc that
  repeats the signature, a step comment that repeats the step's own `name:`.
- LLM boilerplate: "This class represents…", "This method is responsible for…", "Helper method
  used to…", "Utility method for…", "It is important to note…".
- Parameter-by-parameter narration of an obvious signature.
- Getters, setters, constructors, builders, records, simple delegation, mapping, logging,
  validation and dependency injection — unless something there is genuinely surprising.

**Comment the shape that invites a wrong "simplification."** Where code is deliberately unusual
because a simpler form is broken, one line naming the trap is worth more than a paragraph
elsewhere — a pipeline that must not be collapsed, a strict-mode flag that must not be added, a
seemingly redundant verification step. Both fail-open defects in the email guard were introduced
by exactly that kind of tidy-up.

**Per file type:**

- **Java** — JavaDoc on public API only. Carve-out: JavaDoc on Mojo `@Parameter` fields is
  harvested into `plugin.xml` and *is* the `mvn help:describe` and site documentation — keep it,
  however obvious it looks. Editing `FILE_HEADER` changes generated output, so rail 4 applies.
- **Shell** — see `docs/shell-code-style.md`. `|| true` and any other tolerated failure needs its
  reason stated at the call site.
- **Workflow YAML** — the step's `name:` is the description; comment only why a step exists, why a
  permission is elevated, or why an action is pinned by digest.
- **POM XML** — comment Maven traps and inheritance effects (silently dropped attributes,
  `dependencyManagement` reaching BOM consumers), never what a plugin plainly does.
- **`docs/`** — documentation, not comments; the rules above don't apply, but concision does.

**Never cite the private side from a committed file** — no path, filename or section number of a
private record, in a comment or anywhere else (rail 1). Committed comments must stand on their own.

## Build, test and quality gate — before every commit of code

Compiles at release 17 (`<java.release>`, enforcer floor `[17,)`) — write Java 17, not 21. CI
runs `mvn -B verify` on JDK 17, 21 and 25.

`mvn -B verify` runs the unit tests plus the invoker IT, which generates against
`jpa-metadata-maven-plugin/src/it/simple-consumer` and byte-compares the golden corpus. Tests
are JUnit 5 + AssertJ + Mockito over fixtures in
`jpa-metadata-maven-plugin/src/test/resources/projects/simple-project`;
`GenerationReproducibilityTest` generates twice and asserts byte-identical, date-free output —
extend it when generator output changes.

1. Run IDE inspections on every file you changed (`mcp__idea__get_file_problems`, or
   `mcp__idea__lint_files` for a batch). Fix all errors, and all warnings unless you can state
   why the warning is wrong. Fix the cause — no `@SuppressWarnings` or other silencing.
2. Reformat only files you touched (`mcp__idea__reformat_file`); never bulk-reformat, and never
   reformat `src/it/**/expected/**` or generated sources (rail 4). Style is `.editorconfig`;
   change it deliberately, never as a side effect.
3. `mvn -B verify` must pass.
4. If you touched a shell script or a workflow `run:` block: `shellcheck -S style` must pass, and
   re-run the script's controls asserting **stderr as well as exit codes** (`docs/shell-code-style.md`
   §8). Workflow YAML changes get parsed before commit — a broken `run:` block only surfaces in CI.
5. The IDE MCP (`mcp__idea__*`) is available whenever IntelliJ has this project open — which is
   the normal state here. In a harness that defers tool schemas (Claude Code), the tools appear
   name-only until loaded (ToolSearch); a deferred schema is **not** unavailability. Declare the
   IDE MCP unavailable only after an actual call has failed — then say so explicitly, fall back to
   `mvn -B verify` plus `mcp__ide__getDiagnostics`, and never report inspections as run when they
   were not.
