# Changelog

## Unreleased

The `entity-metamodel` family — `core`, `runtime`, `runtime-r2dbc`, `processor` and a BOM — is built
in this repository and **not published**. It holds the model vocabulary, the reference types
(`EntityRef`, `PropertyRef`, `JoinRef`) that generated code compiles against, the owned `@Generated`
and `@RawSql` markers, and the annotation processor that generates metamodels. The supported
substrate is `spring-data-relational` / `spring-data-r2dbc` 4.0.x–4.1.x, the lines still in OSS
support, so an application on the 3.5.x line upgrades before adopting the runtime.

**The public API is frozen (2026-09-06) and mechanically gated.** The public signatures of `core`,
`runtime` and `runtime-r2dbc` are held by a compatibility gate that runs inside `mvn verify` and in
CI: Revapi compares every build against the latest released version — until a first release exists
it compares against an empty baseline, which is exactly what lets its foreign-type check enforce
from day one — and per-module architecture tests pin the precise admit list of non-JDK types allowed
on a public signature, the ban on Spring's rendering internals, and the reactive prohibitions
(no blocking, scheduling, subscribing or error-swallowing in library code). Intentional breaks,
once something is published, ride an auditable justification ledger in the module's build
configuration. Two API touches landed with the freeze: the ambiguity refusal in the `one(…)`
terminals now raises an owned `NonUniqueRowException` (an `IllegalStateException` subtype), so
catching it no longer swallows the distinct fault of reading a side an outer join did not match —
that one stays a plain `IllegalStateException` — and the generation SPI's reporting seam gained
`warning(String)`, mapped to the compiler's warning level by the processor. One accepted
source-level quirk enters the frozen baseline: `all(select, null)` with an untyped `null` is a
compile-time ambiguity between the paging and mapper overloads; it previously compiled into a
guaranteed runtime failure, so the ambiguity is the better error. The integration suite also runs
under a blocking-call detector; it found, and the docs now carry, one substrate behavior worth
knowing: Spring Data's projection introspection reads the projection's class file from disk on
first use per type, on the event loop, cached afterwards — a first-call latency cost on
`readProjection`.

**`entity-metamodel-processor` generates.** It reads `@Table` types through `javax.lang.model`
rather than by parsing sources — discovery needs the `@Table` annotation itself, since the compiler
does not present types annotated with a stereotype composed over it, though such a type *is*
recognised as an entity when another entity refers to it — and emits one metamodel per entity:
inherited members flattened into the entity and re-anchored there, nested entities mirrored as
nested metamodels, exact declared types — generics, arrays, bounded wildcards — carried into the ref
type arguments, and members ordered by name so the output does not depend on the compiler. Inclusion
follows Spring's persistent-property rules, with `entitymetamodel.requireColumnAnnotation` as the
strictness opt-in. It declares no third-party dependency, and registers in the *isolating* category
so a build tool can regenerate one file rather than all of them — asserted by a test, and measured
end to end on Gradle: editing a supertype regenerates the metamodels that inherit from it, editing
an entity keeps its inherited members, and editing nothing recompiles nothing. Every member it
cannot express yet — embedded values, references to other aggregates, generic entity types — is
reported instead of dropped in silence, as is a member whose type the generated class could not
name: a private nested type, or a non-public one inherited from another package. A member name that
two properties would share is an error rather than a duplicate field, and so is an entity whose
simple name contains `__` or ends with `_`: `EntityRef` refuses both as an alias — the first
collides with the projected-label separator, the second lets one instance's label prefix begin
another's — so the metamodel would compile and then fail in its static initializer. A member is recognised as a relationship when the type it
refers to is itself a mapped entity — not by `@MappedCollection`, which Spring treats as optional.
Whether any other non-simple type is one column or another aggregate depends on the converters a
context registers, so that answer comes from the context at resolution time. A committed corpus of
golden files holds the output byte-for-byte on JDK 17, 21 and 25.

`PropertyRef.columnName` now refuses a relationship property instead of returning a column name the
table does not have. Whether a given value type is one column or another aggregate depends on the
converters registered on the mapping context, so the answer comes from the context at resolution
time rather than from a guess at generation time.

**`entity-metamodel-runtime-r2dbc` executes.** A description of a select — the entity, its joins,
its filter, its sort and its page — is built as an immutable value that performs no I/O, rendered to
SQL as a pure function of that value, and run by one executor the consumer wires themselves. What it
covers: inner and left-outer joins from declared relationships or from a condition the caller
states; the same table joined any number of times under distinct instances, with filters on the same
column of each; bind values inside a `JOIN … ON`; a filter vocabulary of equality, ranges, `IN`,
`LIKE`, null tests, column-to-column equality and negation, composed with `and`/`or` and
parenthesised by construction so an `OR` cannot widen a match by re-associating; a mirror count that
reuses the page's own conditions; two instances of one table hydrated from a single row, each from
its own labels; a typed sort, a sort whose property arrives as text from a `Pageable`, and a
raw-expression sort; `Page`, `Slice`, `list`, `one`, `first`, `count` and `exists` terminals
returning cold publishers, with no scheduler, timeout, retry or transaction anywhere in the library.
A `Criteria` an application already builds is accepted with its meaning intact — every comparator,
groups nested to any depth, a chain folded under SQL precedence rather than left to right, and an
empty selection, which matches nothing exactly as it does through the substrate's own template. Its
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
page" would otherwise say no while distinct rows remain. A join still expands rather than restricts:
asking for "parents that have a child" is an `EXISTS` fragment through the raw door, which joins
nothing and multiplies nothing.

Two things it deliberately does not do. It has no aggregate API: a raw-SQL door takes the cases the
typed vocabulary cannot express, and `{0}` in one of those fragments names either a value to bind or
a property whose column the library writes itself — so a fragment never spells out a table alias.
References are numbered rather than positional `?` because a question mark is an operator in the
dialects this targets: PostgreSQL spells jsonb key existence `?`, `?|` and `?&`, and no parser can
tell those from a placeholder. Everything that is not `{digits}` is literal text, so those operators
are now writable; an argument may be named more than once, and one nothing names is refused. And it
never chooses a thread: no substrate SQL type appears in a public signature, and nothing about
latency, retries or caching is decided for the consumer.

Where a projected label would pass the 63 bytes PostgreSQL silently truncates identifiers to, the
whole statement switches to positional table aliases. Without that, two labels agreeing in their
first 63 bytes become one column and an entity hydrates with a null field whose value is in the row
— demonstrated against PostgreSQL 16, which is why the rule is whole-statement rather than
per-table. A column name so long that no alias leaves room for a prefix is reported when something
projects it, instead of being rendered and truncated — joining such a table to filter on it is
unaffected, because no label of its is emitted.

A schema an entity declares now reaches the statement. The table resolves through the mapping
context's *qualified* name, which is what Spring's own template targets, so for an entity declaring
`schema` the two no longer disagree. This is broader than the `@Table` attribute: a `NamingStrategy`
default qualifies every entity, including entities that declare none, so a deployment routing by
`search_path` finds those queries pinned to that schema. Permanently — the context resolves the
default once per entity and caches it, which rules it out for per-tenant routing however dynamic the
strategy looks. Only a SpEL `@Table` attribute is re-read per render. The pinning is the substrate's
own, not this library's: Spring's template reads the same cached name, so repository reads and
writes were already frozen to the first caller's schema, and this executor now matches them rather
than silently diverging.

The legacy `@Table("schema.table")` spelling, which the mapping context keeps as one identifier, is
split on its single dot so it names the same relation. The analysis runs on the entity's own name
rather than the qualified one, so a schema spelled into the name beats a strategy default instead of
being stacked under it — stacking the two named a relation that cannot exist. A name the split
cannot group, having more dots or a side that is empty or only whitespace, is handed over as the
entity spelled it and without the default. Such a three-part name resolves only under
`setForceQuote(false)`, not the default, and then only when the catalog is the current database.

The price is stated rather than hidden. A table whose name genuinely contains a dot must name its
schema in the annotation, which turns the split off for that entity and fixes the schema in source,
out of reach of both `search_path` and a strategy default; a blank `schema` attribute counts as no
attribute, matching the mapping context. Left unsaid the cost would be worse than a missing table:
where a schema shares the leading segment's name and holds the trailing one, the query reads a real
but wrong relation. The split is this library's read path alone — Spring's template renders the
dotted name whole, so an application writing through a repository and reading through this executor
reaches two different relations unless the entity states `schema`.

A `@Table` name or schema written as a SpEL expression is evaluated by the substrate on every
resolution, so a terminal reads it when it renders. One rule fixes both when that happens and how a
failure to resolve arrives, and it is the argument list rather than a list of names: **a terminal
that takes a `Pageable` defers, and everything else renders at call**. So `page`, `slice` and the
windowed `all` resolve on subscribe and report a failure as an error signal — request data needs a
failure channel — while `all`, `list`, `one`, `first`, `count` and `exists` resolve when they are
called and throw from the call, their mapper forms included, since taking a mapper is not what moves
a terminal's failures into its publisher. A publisher described under one tenant and subscribed
under another therefore reads the first tenant's relation through the terminals that render at call,
and the second's through the three that defer. That split is unchanged — the table name has always
resolved at render — but the schema attribute now travels the same path.

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

Every row-returning terminal now has a mapper form. `one`, `first` and `list` join `all`, `page` and
`slice` in taking a `Function<ProjectedRow, R>`, so a row carrying more than one instance reaches
the single-row terminals without being rebuilt out of `all(...)`: `one` keeps its named
`IllegalStateException` for an ambiguous match, where the hand-assembled `all(...).singleOrEmpty()`
substitute reported Reactor's `IndexOutOfBoundsException` and named neither the cause nor the way
out. Empty, unique and ambiguous results behave exactly as they do for the entity forms. What the
message carries the type does not: a mapper reading an instance an outer join did not match raises
`IllegalStateException` too, so catching that type broadly around the mapped `one` turns a mapper
bug into an empty answer.

`all` also takes a `Pageable`, returning the window as a `Flux` rather than wrapped in a `Page` or a
`Slice`. It is the streaming terminal for the paged path: the request's sort leads, the window is
applied, and nothing else is fetched — no count statement and no row probing for a successor, so a
listing that displays neither pays for neither. Where a total or a successor *is* wanted, `page` and
`slice` remain the terminals; assembling this shape out of `slice` instead cost a wrapper, an eager
collect and a probe row the caller never asked for. What the wrapper bought is the other half of the
trade: `page` and `slice` collect their rows and release the connection, while a stream holds it
until the subscriber has finished. A downstream that queries from inside the stream therefore needs
a connection to do it on — outside a transaction that is pool headroom, and inside one there is
none to be had, since two statements at once on one connection is a protocol error. An unpaged
request applies the sort alone and leaves whatever window the description carries in place, and a
request that brings its own window to a description that already bounds itself is refused as a
collision, exactly as for `page` and `slice`.

A row can also be read back as a projection rather than as a whole entity: `readProjection` takes a
closed interface whose accessors name the instance's properties, or a DTO, so a listing can carry
two columns of a joined table without hydrating it. The select list is unchanged — a projection
narrows the object, not the query. The two forms narrow differently: a DTO reads only the properties
it declares, while an interface projection reads and converts every property of the instance and
narrows only the object handed back, so a column whose reading converter rejects the stored value
fails an interface projection that never names it. Absence works as it does for an entity: an
instance the outer join did not match is empty rather than a proxy answering null to everything. Two
shapes are refused rather than served quietly, both on interface projections, whose accessors are
the whole of what they read. An open projection, whose values come from a `@Value` expression
instead of a column, is refused because the expression is not evaluated here. And an accessor naming
a property the entity does not persist is refused naming it — an interface has no compile-time link
to the entity, so renaming a property would otherwise leave every projection still compiling and
answering null for that field on every row. A DTO is bound by the substrate from its own fields,
which may carry their own `@Column`, bind without accessors, or be computed locally; it is passed
through unchecked, so a stale field reads null there, a stale primitive constructor parameter fails
to bind, and a stale primitive field quietly takes the type's default — exactly as through the
substrate, and a `@Value` constructor parameter is evaluated rather than refused. Where an entity is
built by a registered `Converter<RowDocument, T>`, projecting it is refused outright rather than
silently bypassing that converter: the two doors would otherwise disagree about the same row, and
where the converter redacts a column the projection is the one that leaks it. The substrate's own
template does bypass it — the same projection through an `R2dbcEntityTemplate` succeeds and returns
the stored value — so this is a deliberate difference rather than a wart being fixed, and code
migrating off the template meets it as a refusal where it previously got an answer.

Because rows are materialised from their own columns rather than through an entity template, only
part of Spring's read machinery applies, and the boundary is worth stating. Property-level reading
converters work on both doors. An entity-level `Converter<RowDocument, T>` applies when reading a
whole entity but is not consulted when projecting, which reads properties directly — so a converter
that decrypts or redacts a column does not cover a projection of it. An entity-level `Converter<Row,
T>` and `AfterConvertCallback` never run at all — the first is consulted only where a driver row is
the source, and the second needs callbacks resolved from an application context, which this library
neither registers nor holds. Logic that has to run after an entity is read belongs in the mapper the
executor takes.

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
