# Runbook — Golden-Corpus Update

## Status

The golden corpus is `entity-metamodel-processor/src/test/resources/contract-corpus/expected/` —
**six expected `.java` files** under `com/example/contract/`, byte-compared by
`ContractCorpusParityTest` during `mvn verify`. It freezes the **D1 generated shape**: metamodels
land in the entity's own package, reference runtime-library types, and emit nothing into a package
this project does not own.

That shape is settled, not provisional: the generated-shape and member-ordering decisions were both
ratified on 2026-08-18, and the processor reproduces the corpus byte-for-byte. The corpus is
therefore a contract, and a diff against it is a contract change rather than a test failure.

*(Until 2026-08-30 this runbook governed the 1.x plugin's corpus, which froze the superseded shape —
including the two infrastructure files that generator emitted into Spring's own packages. That
module was removed; the procedure below is unchanged, because the control it expresses did not
depend on which generator produced the output.)*

## Rules

1. **No corpus change without an approving decision record.** It must exist and be dated
   before the change is staged, and the **commit message must name it**. Decision records
   are not committed to this repository, so the written-decision discipline is enforced by
   the commit message rather than by a same-commit diff.
2. Generated output must remain byte-deterministic: no dates, no absolute paths, no
   environment-dependent content, and a stable member order.
3. Every corpus file must compile before ratification — the parity test compiles the corpus's
   own sources against real Spring Data artifacts and diffs what the processor emits. Never accept
   hand-authored expected sources on eyeball review.

## Procedure

```bash
# 1. Make the processor change on a feature branch (feature/YYYY.MM_desc).
# 2. Run the parity test and inspect the byte-diff it reports:
mvn verify
# 3. If the diff is exactly the intended shape change, update the expected sources,
#    then prove determinism by running twice — both must be green and identical:
mvn verify && mvn verify
# 4. Stage explicitly (never git add .):
git add entity-metamodel-processor/src/test/resources/contract-corpus/expected/...
```

## Red flags — stop and escalate

- A corpus diff you did not intend → determinism regression; find the source of
  non-determinism before touching expected files.
- A diff that appears only on some JDKs or only in an IDE build → compiler-dependent element
  ordering. This is a known hazard (javac vs ECJ, and binary-sourced types); it is why the
  processor imposes its own total order instead of relying on declaration order.
- Updating expected files "to make the build green" with no approving record — that turns a
  contract change into an accident, which is what this gate exists to prevent.
