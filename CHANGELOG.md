# Changelog

## Unreleased

The `entity-metamodel` family — `core`, `runtime`, `processor` and a BOM — is built in this
repository and **not published**. It holds the model vocabulary, the reference types (`EntityRef`,
`PropertyRef`, `JoinRef`) that generated code compiles against, the owned `@Generated` and
`@RawSql` markers, and the annotation processor that generates metamodels.

**`entity-metamodel-processor` generates.** It reads `@Table` types through `javax.lang.model`
rather than by parsing sources — discovery needs the `@Table` annotation itself, since the compiler
does not present types annotated with a stereotype composed over it, though such a type *is*
recognised as an entity when another entity refers to it — and emits one metamodel per entity: inherited members flattened
into the entity and re-anchored there, nested entities mirrored as nested metamodels, exact
declared types — generics, arrays, bounded wildcards — carried into the ref type arguments, and
members ordered by name so the output does not depend on the compiler. Inclusion follows Spring's
persistent-property rules, with `entitymetamodel.requireColumnAnnotation` as the strictness opt-in.
It declares no third-party dependency, and registers in the *isolating* category so a build tool can
regenerate one file rather than all of them — asserted by a test, and measured end to end on Gradle:
editing a supertype regenerates the metamodels that inherit from it, editing an entity keeps its
inherited members, and editing nothing recompiles nothing. Every member it cannot express yet — embedded values, references to
other aggregates, generic entity types — is reported instead of dropped in silence, and a member
name that two properties would share is an error rather than a duplicate field. A member is
recognised as a relationship when the type it refers to is itself a mapped entity — not by
`@MappedCollection`, which Spring treats as optional. Whether any other non-simple type is one
column or another aggregate depends on the converters a context registers, so that answer comes from
the context at resolution time. A committed corpus of golden files holds the output byte-for-byte on JDK 17, 21 and 25.

`PropertyRef.columnName` now refuses a relationship property instead of returning a column name the
table does not have. Whether a given value type is one column or another aggregate depends on the
converters registered on the mapping context, so the answer comes from the context at resolution
time rather than from a guess at generation time.

**`entity-metamodel-runtime-r2dbc` executes.** A description of a select — the entity, its joins,
its filter, its sort and its page — is built as an immutable value that performs no I/O, rendered to
SQL as a pure function of that value, and run by one executor the consumer wires themselves. What it
covers: inner and left-outer joins from declared relationships or from a condition the caller states;
the same table joined any number of times under distinct instances, with filters on the same column
of each; bind values inside a `JOIN … ON`; a filter vocabulary of equality, ranges, `IN`, `LIKE`,
null tests, column-to-column equality and negation, composed with `and`/`or` and parenthesised by
construction so an `OR` cannot widen a match by re-associating; a mirror count that reuses the page's
own conditions; two instances of one table hydrated from a single row, each from its own labels; a
typed sort, a sort whose property arrives as text from a `Pageable`, and a raw-expression sort;
`Page`, `list`, `one`, `first`, `count` and `exists` terminals returning cold publishers, with no
scheduler, timeout, retry or transaction anywhere in the library. A `Criteria` an application already
builds is accepted unchanged, so a filter layer that works does not have to be rewritten — including
an empty selection, which matches nothing exactly as it does through the substrate's own template.

One thing to know before joining: a join to a to-many side multiplies rows, so the selected entity
comes back once per matching counterpart — a list carries duplicates and a page's total counts those
rows rather than entities. That is what the SQL says, and there is no `DISTINCT` to undo it, so a
join here expands rather than restricts; asking for "parents that have a child" is an `EXISTS`
fragment through the raw door.

Two things it deliberately does not do. It has no aggregate API: a raw-SQL door takes the cases the
typed vocabulary cannot express, and every `?` in one of those fragments takes either a value to bind
or a property whose column the library writes itself — so a fragment never spells out a table alias.
And it never chooses a thread: no substrate SQL type appears in a public signature, and nothing about
latency, retries or caching is decided for the consumer.

Where a projected label would pass the 63 bytes PostgreSQL silently truncates identifiers to, the
whole statement switches to positional table aliases. Without that, two labels agreeing in their
first 63 bytes become one column and an entity hydrates with a null field whose value is in the row —
demonstrated against PostgreSQL 16, which is why the rule is whole-statement rather than per-table.
A column name so long that no alias leaves room for a prefix is reported when something projects it,
instead of being rendered and truncated — joining such a table to filter on it is unaffected, because
no label of its is emitted.

A page request's sort leads any sort the description already carries, and a sort property the entity
does not persist arrives as an error signal on the returned publisher rather than as a thrown
exception — it is request data, so a handler can map it to a bad-request response.

Nothing is published yet. The first publication will be the version that generates *and* executes,
rather than a milestone of parts — see [`ROADMAP.md`](ROADMAP.md).

## 1.1.0 — 2026-08-15

First release since the plugin was renamed. The theme is trustworthy output: generation is now
reproducible, generated sources no longer land in the consumer's jar, and an integration test
holds the generated shape still.

### Changed

- **`outputDirectory` default moved** from `${project.build.outputDirectory}` (`target/classes`)
  to `${project.build.directory}/generated-sources/metamodel`. Generated `.java` files are no
  longer packaged into the consumer's jar. Builds that read generated sources from
  `target/classes` must use the new location or set `outputDirectory` explicitly.
- **Generated output is deterministic.** Column constants are emitted in declaration order, which
  was previously unspecified and could vary between runs, and file headers no longer embed a
  generation date. Regenerating over unchanged sources is byte-identical.
- **Generated file headers** name the actual artifact, `jpa-metadata-maven-plugin`, replacing the
  stale `entity-metadata-plugin`.
- **Java baseline is 17** (1.0.0 emitted Java 21 bytecode), so the plugin runs on JDK 17 builds.
  The build enforces Maven 3.9+ and JDK 17+.
- **`entityMetadataGenerator` is no longer marked required.** It defaults to `r2dbc` and only needs
  setting when another generator is on the plugin's classpath.
- **Release tags are `v`-prefixed** from this release on: `v1.1.0`, where 1.0.0 was tagged `1.0.0`.

### Added

- Integration test (`src/it/simple-consumer`) that runs the plugin over the README entities,
  compiles the generated sources, and diffs them against a committed golden corpus. Any change to
  the generated shape now has to be deliberate.
- `GenerationReproducibilityTest`, asserting byte-identical regeneration and date-free headers
  across every fixture package.
- CI on pull requests and `master` across JDK 17, 21 and 25. The previous workflow was gated to
  `feature/**` and `bug/**` branches, so it never ran on either.
- A separate, manual-dispatch release workflow. The old tag trigger raced `maven-release-plugin`,
  which creates the tag itself.
- `LICENSE` (Apache 2.0), matching the licence the pom already declared.

### Fixed

- The pom description contained `@Table and @Column`. Maven reads `@...@` as a filter placeholder,
  so `@Table and @` parsed as an unresolvable expression.
- README documented `UserAttribute_.VALUE`; the generated constant is `ATTRIBUTE_VALUE`.
- README and the plugin description claimed JPA support. The plugin reads Spring Data Relational's
  `@Table` and `@Column`; `jakarta.persistence` annotations are not supported.
- Field-name collection returned a mutable set and the parameter table, list numbering and licence
  URL in the docs were incorrect.

### Internal

- Dependencies: `reflections` and `maven-project` removed, `maven-core` moved to `provided`, and
  `junit-bom` imported so all JUnit artifacts resolve to one version.
- Guava's `com.google.common.graph` is `@Beta` and appears in the plugin's SPI. The warnings are
  suppressed with the reason recorded in the code; replacing the type is future work.
- Javadoc and comments reduced to what the code cannot express on its own.

### Known limitations

`Column_` is generated into Spring's own `org.springframework.data.relational.core.sql` package,
splitting that package across two artifacts, and `StaticR2dbcEntityTemplateAccessor_` is global
mutable state populated at Spring startup. Both are deliberate for now: consumers compile against
this shape, so it is frozen by the golden corpus until a runtime library replaces it.

## 1.0.0 — 2025-06-19

Initial release, published to GitHub Packages only.

- `generate-metadata` goal, bound to `generate-sources`, scanning a configured package for Spring
  Data Relational `@Table` types and emitting a `<Entity>_` metamodel class per entity.
- Entities parsed from source with JavaParser, so they need not be compiled first. Classes and
  records are both supported, along with nested types and `@Column` fields inherited from
  supertypes in the scanned package.
- Generated `Column_` resolves a field name to its SQL column lazily through the Spring mapping
  context, giving compile-time-checked column references in Spring Data R2DBC queries.
- Generator backends are discovered with `ServiceLoader`, selected by the `entityMetadataGenerator`
  parameter; `r2dbc` ships with the plugin.
