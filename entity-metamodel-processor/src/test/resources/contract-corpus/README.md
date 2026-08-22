# Contract golden corpus

`expected/**` is the frozen generated shape. The processor reproduces it **byte for byte**; the
parity test compares bytes, never syntax trees, because formatting and member order are part of the
contract and an AST comparison passes while they drift.

`sources/**` are the entities the shape is frozen against. Each one exists to pin a decision:

| Fixture | Pins |
|---|---|
| `Account` | inclusion semantics — `nickname` is persistent without `@Column` (in), `draftNote` is `@Transient` despite its `@Column` (out); `sourceURLPath` pins the acronym boundary in `SOURCE_URL_PATH` |
| `Inventory` | exact declared types as ref type arguments — parameterized, array and bounded-wildcard forms |
| `Payment` | primitives box in the ref type argument while the raw-type token stays primitive; enum and temporal types carry as declared; `captured` sorts before `capturedAt` |
| `LegacyDocument` / `BaseDocument` | inherited members flatten into the concrete entity, re-anchored there, interleaved alphabetically — no metamodel for the non-`@Table` superclass |
| `Vendor` | a nested `@Table` type's metamodel is nested inside its owner's, named per level |
| `Wrapper` | a nested `@Table` type whose enclosing class is *not* an entity: the encloser gets no metamodel and the nested type gets a top-level one, prefixed with the encloser's simple name |

## Changing anything here

These bytes are a frozen contract, not test scaffolding. A change to `expected/**` — including
whitespace and comments — needs an approving decision on record first, and the commit message must
name it. Same rule as the 1.x corpus under `jpa-metadata-maven-plugin/src/it/simple-consumer/expected/`;
the procedure is `docs/runbooks/golden-corpus-update.md`.

Generated output must stay byte-deterministic: no dates, no environment-dependent content. The
double-generation test in this module is what enforces it.
