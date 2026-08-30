---
description: Reduce Java comments and JavaDoc to what earns its place
argument-hint: [path or glob — defaults to files changed against master]
---

# Documentation cleanup

A reduction task, not a documentation-generation task. Assume a comment is unnecessary until
it proves otherwise; let the code carry intent. The result should read as if maintained by
engineers who trust expressive code over explanation.

Scope: $ARGUMENTS — if empty, the Java files changed against `master`. Do not edit outside it.

## Never touch

- `**/contract-corpus/expected/**` and the generated-file header constant in `MetamodelWriter`:
  byte-compared by the parity test. A comment edit there breaks the parity gate.
- Licence headers.

## Delete

- Comments describing the next line: `// increment counter`, `// validate input`,
  `// return result`, `// initialize service`.
- JavaDoc that restates the signature — on getters, setters, constructors, builders, records,
  simple delegation, mapping code, stream pipelines, logging, validation, DI wiring.
- LLM-tell openers: "This method is responsible for…", "This class represents…", "The purpose
  of this method is…", "Helper/Utility method for…", "It is important to note…". If striking
  the opener leaves nothing of substance, delete the whole block.
- `@param`/`@return` tags that only repeat the parameter name or the return type.
- Commented-out code.

## Keep, and tighten

A comment survives only if it explains one of: a non-obvious business rule; an architectural
constraint; a workaround for an external dependency; a performance, concurrency, or security
consideration; a compatibility requirement; intentionally surprising behaviour; or the
rationale behind a decision that would otherwise be re-litigated.

Rewrite survivors to one or two sentences of plain technical English — no corporate register,
no tutorial tone, no unnecessary adjectives.

## JavaDoc that stays

Public classes, interfaces, enums, records, and public API methods: one short paragraph, two
sentences maximum. What it does, and why it exists when that is not obvious from the name. No
implementation narration.

## Procedure

1. Read each scoped file in full first — rationale often sits far from what it explains.
2. Edit. When unsure whether a comment encodes a real constraint, keep it and raise it as a
   question instead of deleting it.
3. `mvn -B verify` must pass; the parity test proves the golden corpus is untouched.
4. Report: files touched, comment lines removed, and each comment kept against the rules
   above with the reason.

