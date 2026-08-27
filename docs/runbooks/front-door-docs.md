# Runbook — Front-Door Docs

## Status

`README.md`, `ROADMAP.md` and `CHANGELOG.md` are one story at three zoom levels: the CHANGELOG
records **what exists, in detail**; the ROADMAP records **where it is going and what gates the
order**; the README is **the front door** — what a newcomer needs to adopt or evaluate. They drift
one document at a time: in 2026-08 both README and ROADMAP still called the query surface
"next" / "being built" after it executed, because the CHANGELOG had been updated alone.

## Rules

1. **The CHANGELOG's `Unreleased` section is the detailed record.** The other two condense from
   it and must never contradict it. Update it first, sweep the other two in the same change.
2. **Verify every claim against code, tests and POMs — never against the other two documents.**
   The other documents are what you are fixing. Floors come from the enforcer rules, states from
   the modules and their test suites, examples from the committed corpora.
3. **Committed-file rails apply** (`CLAUDE.md`): no reference to private working material, no
   email addresses, no unattested quantities restated as fact.
4. **Examples mirror committed corpora only.** 1.x: `jpa-metadata-maven-plugin/src/it/simple-consumer/expected/`;
   2.x generated shape: `entity-metamodel-processor/src/test/resources/contract-corpus/expected/`.
   An example that exists nowhere in the repository is fiction with syntax highlighting.

## Procedure

Trigger: anything that changes a capability or a state word — a module appears, a feature starts
executing, a floor moves, publication state changes, a limitation is added or removed.

1. Update `CHANGELOG.md` `Unreleased` with the detail.
2. Sweep `ROADMAP.md`: the as-of date, the status markers (*Built.* etc.), the "what remains"
   sentence, the publication bar.
3. Sweep `README.md`: the "What's here" table states, the capability bullets, the stated
   boundaries, "Where it's going", supported floors, troubleshooting rows.
4. Check the claims that must agree everywhere:
   - publication state (nothing is published until it is);
   - which artifacts exist, by name;
   - state verbs — "built" vs "executes" vs "shipped" mean different things, pick per fact;
   - the publication bar, phrased once and echoed;
   - version floors (Java, Maven, Spring Data range) — re-read the enforcer config and POMs;
   - deliberate boundaries stated next to the capabilities they bound, not hidden.
5. Lint the changed files (IDE inspections) before staging.

## Red flags — stop and check

- A sentence you are about to copy from one front-door document into another without a source in
  code or tests — that is how stale claims propagate instead of dying.
- A capability described in the README with no test behind it — either write the test reference
  down or soften the claim.
- The same fact carrying different state words in two documents after your sweep — the sweep is
  not done.
