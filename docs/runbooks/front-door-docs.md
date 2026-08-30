# Runbook — Front-Door Docs

## Status

`README.md`, `ROADMAP.md`, `CHANGELOG.md` and `SECURITY.md` are one story at four zoom levels: the CHANGELOG
records **what exists, in detail**; the ROADMAP records **where it is going and what gates the
order**; the README is **the front door** — what a newcomer needs to adopt or evaluate; `SECURITY.md`
records **which versions receive fixes**, which is a state word like any other and drifts the same
way. They drift one document at a time: in 2026-08 both README and ROADMAP still called the query
surface "next" / "being built" after it executed, because the CHANGELOG had been updated alone.

## Rules

1. **The CHANGELOG's `Unreleased` section is the detailed record.** The others condense from it and
   must never contradict it. Update it first, sweep the others in the same change.
2. **Verify every claim against code, tests and POMs — never against the other documents.**
   The other documents are what you are fixing. Floors come from the enforcer rules, states from
   the modules and their test suites, examples from the committed corpora.
3. **Committed-file rails apply** (`CLAUDE.md`): no reference to private working material, no
   email addresses, no unattested quantities restated as fact.
4. **Examples mirror the committed corpus only:**
   `entity-metamodel-processor/src/test/resources/contract-corpus/expected/`.
   An example that exists nowhere in the repository is fiction with syntax highlighting.

## Procedure

Trigger: anything that changes a capability or a state word — a module appears, a feature starts
executing, a floor moves, publication state changes, a limitation is added or removed.

1. Update `CHANGELOG.md` `Unreleased` with the detail.
2. Sweep `ROADMAP.md`: the as-of date, the status markers (*Built.* etc.), the "what remains"
   sentence, the publication bar.
3. Sweep `README.md`: the "What's here" table states, the capability bullets, the stated
   boundaries, "Where it's going", supported floors, troubleshooting rows.
4. Sweep `SECURITY.md`: the supported-versions rows and the scope note. A retired or unshipped line
   must not be listed as receiving fixes, and a version no build can resolve must not appear as
   though it can.
5. Check the claims that must agree everywhere:
   - publication state (nothing is published until it is);
   - which artifacts exist, by name;
   - state verbs — "built" vs "executes" vs "shipped" mean different things, pick per fact;
   - the publication bar, phrased once and echoed;
   - version floors (Java, Maven, Spring Data range) — re-read the enforcer config and POMs;
   - deliberate boundaries stated next to the capabilities they bound, not hidden.
6. Lint the changed files (IDE inspections) before staging.

## Red flags — stop and check

- A sentence you are about to copy from one front-door document into another without a source in
  code or tests — that is how stale claims propagate instead of dying.
- A capability described in the README with no test behind it — either write the test reference
  down or soften the claim.
- The same fact carrying different state words in two documents after your sweep — the sweep is
  not done.
