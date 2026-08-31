# Changelog

## Unreleased

The `entity-metamodel` family — `core`, `runtime`, `processor` and a BOM — is built in this
repository and **not published**. It holds the model vocabulary, the reference types (`EntityRef`,
`PropertyRef`, `JoinRef`) that generated code compiles against, the owned `@Generated` and
`@RawSql` markers, and the annotation processor that generates metamodels. The supported substrate
is `spring-data-relational` / `spring-data-r2dbc` 4.0.x–4.1.x, the lines still in OSS support, so
an application on the 3.5.x line upgrades before adopting the runtime.

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
`Page`, `Slice`, `list`, `one`, `first`, `count` and `exists` terminals returning cold publishers,
with no scheduler, timeout, retry or transaction anywhere in the library. A `Criteria` an
application already builds is accepted with its meaning intact — every comparator, groups nested
to any depth, a chain folded under SQL precedence rather than left to right, and an empty
selection, which matches nothing exactly as it does through the substrate's own template. Its
references name properties rather than columns: a column name can be another property's name, so
resolving one as the other would filter the wrong column with nothing to notice. A criteria that
names a column is refused, and the refusal names the property to use instead.

One thing to know before joining: a join to a to-many side multiplies rows, so the selected entity
comes back once per matching counterpart — a list carries duplicates and a page's total counts those
rows rather than entities. `distinct()` collapses that back to distinct projected rows, and the
derived statements keep the meaning: the total counts the distinct selection itself as a derived
table, because no dialect-portable `COUNT` expression says "distinct over these columns" once the
projection has more than one, and the exists probe keeps the real projection, because `DISTINCT`
over a literal collapses to one row before an offset applies — a probe asking "is there another
page" would otherwise say no while distinct rows remain. A join still expands rather than
restricts: asking for "parents that have a child" is an `EXISTS` fragment through the raw door,
which joins nothing and multiplies nothing.

Two things it deliberately does not do. It has no aggregate API: a raw-SQL door takes the cases the
typed vocabulary cannot express, and `{0}` in one of those fragments names either a value to bind or
a property whose column the library writes itself — so a fragment never spells out a table alias.
References are numbered rather than positional `?` because a question mark is an operator in the
dialects this targets: PostgreSQL spells jsonb key existence `?`, `?|` and `?&`, and no parser can
tell those from a placeholder. Everything that is not `{digits}` is literal text, so those operators
are now writable; an argument may be named more than once, and one nothing names is refused.
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

`page` takes a mapper alongside `all`, so a projected or multi-entity listing gets its page and
total from the library rather than being assembled by hand — the content and the count run in
sequence, which is what a shared connection inside a transaction requires and what a hand-rolled
`zip` gets wrong. `distinct()` collapses whole selected rows, not what a mapper narrows them to.

`slice` joins those terminals for the listing that never displays a total. It fetches one row beyond
the requested page and reports a successor from whether that row arrived, so it issues **no count
statement at all** — on a large table the count is frequently dearer than the page itself, and an
infinite-scrolling listing never shows the total it paid for. It takes a mapper alongside the entity
form exactly as `page` does. `Page` already extends `Slice`, so what this buys is the avoided
statement rather than a new shape. The extra row is dropped before anything hydrates it, and it is
one row beyond the page in `long` arithmetic, so the largest legal page size does not wrap the limit
negative. An unpaged request comes back as a single slice reporting no successor: there is no next
page to ask for, and unlike `page` there is no total to say otherwise — so a description carrying
its own limit answers "no successor" while rows remain behind it. Rows, not entities, exactly as for
a page: a to-many join multiplies both the page and the row that probes past it.

`page` now counts only where the page cannot imply the total. A first page that did not fill, and a
non-empty partial page past the beginning, both determine the total from the rows in hand, so those
requests issue one statement where they used to issue two. An empty page past the beginning still
counts: coming back empty says the offset overshot, not by how much. An unpaged request over a
description carrying its own limit or offset still counts, because the rows it left behind are not
in hand to be counted.

A row can also be read back as a projection rather than as a whole entity: `readProjection` takes a
closed interface whose accessors name the instance's properties, or a DTO, so a listing can carry
two columns of a joined table without hydrating it. The select list is unchanged — a projection
narrows the object, not the query. The two forms narrow differently: a DTO reads only the
properties it declares, while an interface projection reads and converts every property of the
instance and narrows only the object handed back, so a column whose reading converter rejects the
stored value fails an interface projection that never names it. Absence works as it does for an
entity: an instance the outer join did not match is empty rather than a proxy answering null to
everything. Two shapes are refused rather than served quietly, both on interface projections, whose
accessors are the whole of what they read. An open projection, whose values come from a `@Value`
expression instead of a column, is refused because the expression is not evaluated here. And an
accessor naming a property the entity does not persist is refused naming it — an interface has no
compile-time link to the entity, so renaming a property would otherwise leave every projection
still compiling and answering null for that field on every row. A DTO is bound by the substrate
from its own fields, which may carry their own `@Column`, bind without accessors, or be computed
locally; it is passed through unchecked, so a stale field reads null there, a stale primitive
constructor parameter fails to bind, and a stale primitive field quietly takes the type's default —
exactly as through the substrate, and a `@Value` constructor parameter is evaluated rather than
refused. Where an entity is built by a registered `Converter<RowDocument, T>`, projecting it is
refused outright rather than silently bypassing that converter: the two doors would otherwise
disagree about the same row, and where the converter redacts a column the projection is the one
that leaks it. The substrate's own template does bypass it — the same projection through an
`R2dbcEntityTemplate` succeeds and returns the stored value — so this is a deliberate difference
rather than a wart being fixed, and code migrating off the template meets it as a refusal where it
previously got an answer.

Because rows are materialised from their own columns rather than through an entity template, only
part of Spring's read machinery applies, and the boundary is worth stating. Property-level reading
converters work on both doors. An entity-level `Converter<RowDocument, T>` applies when reading a
whole entity but is not consulted when projecting, which reads properties directly — so a converter
that decrypts or redacts a column does not cover a projection of it. An entity-level
`Converter<Row, T>` and `AfterConvertCallback` never run at all — the first is consulted only where
a driver row is the source, and the second needs callbacks resolved from an application context,
which this library neither registers nor holds. Logic that has to run after an entity is read
belongs in the mapper the executor takes.

Nothing is on Maven Central yet. The first publication there will be the version that generates
*and* executes, rather than a milestone of parts — see [`ROADMAP.md`](ROADMAP.md).

**The 1.x Maven plugin is retired and removed.** `jpa-metadata-maven-plugin` was deprecated, then
deleted from the repository along with its golden corpus, its integration test and its dependabot
entry. It had no consumers: it was published to GitHub Packages at 1.0.0, never to Maven Central,
and the owner's own services hand-wrote the capability rather than using it. The module survives in
git history and in the `1.0.0` and `v1.1.0` tags. Its successor is the annotation processor,
declared in maven-compiler-plugin's `annotationProcessorPaths`; the parameters do not map across one
for one — discovery becomes annotation-driven, output and source roots and language level become
compiler settings, and the generator-selection parameter has no successor.

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
