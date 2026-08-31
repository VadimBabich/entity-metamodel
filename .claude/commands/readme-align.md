---
description: Align README and the front-door docs with what the current change actually altered
argument-hint: [git ref or path — defaults to the working tree plus commits since master]
---

# README alignment

Act as the maintainer writing the project's front door. The README is not a specification, a design
document or API documentation: a developer should see what this is for within thirty seconds and
want to try it. Calm, concrete, one engineer to another.

This is an alignment pass, not a rewrite for its own sake. Work out what the change altered, then
fix every document that now describes something else.

Scope: $ARGUMENTS — if empty, the working tree plus the commits on this branch since `master`.
`docs/runbooks/front-door-docs.md` is the sweep procedure; this is the trigger and the standard.

## 1. Read the change before the docs

`git diff master...HEAD` and `git status`. Classify what moved:

- a capability appeared, changed shape, or was removed
- a state word changed — "built" / "executes" / "shipped" / "published" mean different things
- a version floor moved (Java, Maven, Spring Data) — re-read the enforcer rules and POMs
- a boundary or limitation was added or removed
- publication state changed

If none of those moved, the front door is already correct. Say so and stop — do not edit for
tidiness.

## 2. Verify against the code, never against the other documents

They are what you are fixing; copying between them is how stale claims propagate instead of dying.

- Capabilities from the modules and their test suites. Floors from the enforcer rules and POMs.
- Examples mirror `entity-metamodel-processor/src/test/resources/contract-corpus/` or a committed
  test fixture. An example that exists nowhere in the repository is fiction with syntax
  highlighting.
- A capability with no test behind it gets the test named or the claim softened.
- Nothing is published: never write an install step implying an artifact on Central exists.
- Cross-check the design intent against the maintainer-local records mapped in `CLAUDE.local.md`.
  Read them; never cite them. A committed file may state a *reason*, never where it is written.
  No email addresses. Quantities from private evidence keep their provenance marker.

## 3. Rewrite the affected sections to this standard

The narrative is a story, not a list: problem → what is different → five-minute example → core
ideas → what it supports → what it deliberately does not → how it compares → recipes → install →
IDE → troubleshooting → where it is going → documentation → contributing.

A section earns its place by answering one of: why this exists, what it solves better, why it is
different, how to use it in five minutes, what it supports, what it will never do, how to
integrate it, where to report problems. If it answers none, cut it.

- The five-minute example matters most: smallest useful code, one idea per block, a sentence on
  why each block matters. Thirty meaningful lines beat two hundred exhaustive ones.
- Show value through examples, not adjectives. Short paragraphs, plain English, zero filler.
- Banned: "aims to", "powerful and flexible", "enterprise-grade", "best-in-class", "robust
  solution", "it is important to note", "in today's software development".
- Compare concepts, never attack products.
- State limitations plainly — that section is what makes the rest credible.
- Leave out internal package structure, class diagrams, architectural layers, and any benchmark
  without evidence behind it.
- Link deeper documents rather than duplicating them. Wrap prose at 100 columns.

## 4. Sweep the siblings in the same change

`CHANGELOG.md` `Unreleased` carries the detail and is updated first; `ROADMAP.md` carries direction
and gates; `SECURITY.md` carries which versions receive fixes. Never update one alone. After the
sweep, publication state, artifact names, state verbs, floors and stated boundaries must read the
same in all four.

## 5. Check

Lint the changed files (IDE inspections). Confirm every relative link resolves. If code changed
too, `mvn -B verify` still has to pass.

## Report

What the change altered, which claims you verified, which you corrected, what you cut, and anything
softened for want of a test.
