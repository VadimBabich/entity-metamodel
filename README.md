# entity-metamodel

Compile-time metamodels for Spring Data R2DBC entities, and typed queries — joins included — that
the compiler checks against them.

> **`2.0.0-RC1` is on Maven Central.** It is a release candidate: the public API froze on 2026-09-06,
> and from this version the compatibility gate compares every build against a released baseline. A
> break before `2.0.0` can only enter through a justified ledger entry, and would be in the CHANGELOG.
> [Where it's going](#where-its-going) has what stands between the candidate and `2.0.0`.

## Why this exists

Spring Data R2DBC takes property names as strings:

```text
Criteria.where("ownerEmail").is(email);
```

The compiler cannot see inside that string. Rename the field, change the `@Column`, delete the
property: the build stays green and the query fails at runtime, usually on the one path nobody
exercised locally. Grep is the only refactoring tool that works, and it misses the name that was
concatenated.

Joins cost more. Spring's SQL DSL can express them, but the fluent `Query`/`Criteria` API has no
join vocabulary, so every repository that touches two tables hand-assembles the same ceremony:
aliased table handles, comparisons built from string lookups, bind markers threaded through `ON`
clauses, per-entity column lists, rows de-multiplexed by hand. Codebases grow an in-house layer
whose only job is carrying that state around.

Both problems have one fix: derive the references from the entities while they compile, and give
joins a vocabulary that carries types. A rename then breaks the build, which is where you want to
hear about it.

## What's different

Hand-written constant classes fix the first half and rot quietly, because nothing keeps them in
step with the entity. Runtime introspection stays in step but moves the error back to runtime,
which is the problem you started with. This library participates in compilation instead, and the
rest follows from that choice.

- **A wrong reference is a compile error.** `PropertyRef<Account, String>` carries the entity, the
  property name and the value type. Comparing against the wrong type, or joining unrelated columns,
  does not compile.
- **Derived from your entities, not from your schema.** A schema-first generator reads the
  database and hands you a second model beside Spring Data's mapping: two places for column names,
  converters and naming rules that nothing keeps consistent. This one reads the entities Spring
  Data already maps.
- **No SQL name is baked into generated code.** Names resolve at use through Spring's own
  `RelationalMappingContext`, so `@Column` and your `NamingStrategy` stay the single source of
  truth for generation, rendering and row reading alike.
- **Ordinary code.** Generated files are plain `.java` on your compile path. Ctrl-click reaches the
  declaration, find-usages works, the debugger steps through them.
- **Reactive, first-party.** Queries run through Spring Data R2DBC's `DatabaseClient` and come
  back as cold publishers. Nothing is bridged over from a blocking SQL DSL.
- **Small on purpose.** The query surface is a closed `SELECT` vocabulary with a raw-SQL door for
  what it cannot say. If aggregates, window functions or set operations are your daily work, a
  full SQL DSL such as jOOQ is the right tool; the two coexist precisely because this one stops
  where full SQL begins.

## Five minutes

The processor goes on the annotation-processor path and never reaches your runtime classpath. The
runtime goes on the compile path, because generated code imports it. Both resolve from Maven
Central.

```xml
<dependency>
  <groupId>io.github.vadimbabich</groupId>
  <artifactId>entity-metamodel-runtime-r2dbc</artifactId>
  <version>2.0.0-RC1</version>
</dependency>
```

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>io.github.vadimbabich</groupId>
        <artifactId>entity-metamodel-processor</artifactId>
        <version>2.0.0-RC1</version>
      </path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

An ordinary Spring Data entity. This one is from the committed corpus, so the metamodel below is
the real output, trimmed only of its private constructor:

```java
@Table("accounts")
public class Account {

  @Id @Column("account_id") Long id;

  @Column("owner_email") String ownerEmail;

  String nickname;

  @Transient @Column("draft_note") String draftNote;

  @Column("source_url_path") String sourceURLPath;
}
```

Compile, and a metamodel appears beside it:

```java
@Generated("io.github.vadimbabich.entitymetamodel")
public final class Account__ {
  public static final EntityRef<Account> ENTITY = EntityRef.of(Account.class);

  public static final PropertyRef<Account, Long> ID = ENTITY.property("id", Long.class);
  public static final PropertyRef<Account, String> NICKNAME = ENTITY.property("nickname", String.class);
  public static final PropertyRef<Account, String> OWNER_EMAIL = ENTITY.property("ownerEmail", String.class);
  public static final PropertyRef<Account, String> SOURCE_URL_PATH = ENTITY.property("sourceURLPath", String.class);
}
```

`nickname` is there without a `@Column`, because the annotation renames a property rather than
deciding membership. `draftNote` is not, because `@Transient` wins. Members come out ordered by
name, so the file never depends on the compiler that produced it. Notice what is absent: no table
name, no column name. Those stay in your entity.

Now a query. `Membership` is the query suite's fixture, a table with an `accountId` column, written
here with the constants the processor generates for it. This joins each membership to the account
that owns it:

```java
JoinRef<Membership, Account> owner = JoinRef.of(Membership__.ACCOUNT_ID, Account__.ID);

FluentSelect<Membership> byOwner = FluentSelect.from(Membership__.ENTITY)
    .join(owner)
    .where(Account__.OWNER_EMAIL.is("owner@example.com"))
    .orderBy(Membership__.ID.desc());

Flux<Membership> rows = executor.all(byOwner);
Mono<Page<Membership>> page = executor.page(byOwner, PageRequest.of(0, 20));
```

`JoinRef.of` compiles only because both sides are `Long`, and `is("…")` only because `OWNER_EMAIL`
is a `String` property. `byOwner` is an immutable value that performs no I/O: describing it touches
no database, and the same description serves the page and its count. Rename `ownerEmail` and this
file stops compiling.

The executor is the one thing you wire yourself. The library registers no component and holds no
static state, so it never guesses which client, context or dialect you meant:

```java
@Bean
MetamodelQueryExecutor metamodelQueryExecutor(
    DatabaseClient databaseClient,
    RelationalMappingContext mappingContext,
    R2dbcConverter converter,
    ConnectionFactory connectionFactory) {

  R2dbcDialect dialect = DialectResolver.getDialect(connectionFactory);

  return new MetamodelQueryExecutor(
      databaseClient, new QueryRenderer(mappingContext, dialect), converter);
}
```

Renderer and converter must share one mapping context. One names a column when projecting it and the
other when reading it back, so two contexts produce rows that nothing claims.

### Where it goes in your code

The executor is an ordinary bean, so it fits wherever your code already lives, including a Spring
Data repository. Put it in the custom fragment and the rest of the interface is untouched: derived
methods and `@Query` methods are a different mechanism and keep working.

```java
public interface MembershipRepository
    extends ReactiveCrudRepository<Membership, Long>, MembershipQueries { }

interface MembershipQueries {
  Flux<Membership> findByOwnerEmail(String email, Pageable pageable);
}

class MembershipQueriesImpl implements MembershipQueries {

  private final MetamodelQueryExecutor executor;

  MembershipQueriesImpl(MetamodelQueryExecutor executor) {
    this.executor = executor;
  }

  @Override
  public Flux<Membership> findByOwnerEmail(String email, Pageable pageable) {
    FluentSelect<Membership> scoped = FluentSelect.from(Membership__.ENTITY)
        .join(JoinRef.of(Membership__.ACCOUNT_ID, Account__.ID))
        .where(Account__.OWNER_EMAIL.is(email));

    return executor.all(scoped, pageable);
  }
}
```

`ownerEmail` belongs to the joined table, so no derived method can express this one, which is what
the fragment is for. `save` and `deleteById` still go through Spring's own machinery.

## Core ideas

- **Compile-time, because the input is compilation.** Annotated Java in the same module is exactly
  the JSR-269 case. Participating in the compile buys resolved types, inheritance, incremental
  builds and Maven, Gradle and IDE support from one artifact, with no build-tool plugin to maintain.
- **A description is a value.** `FluentSelect` is immutable and performs no I/O; rendering it is a
  pure function. The library never schedules, times out, retries, caches or opens a transaction, and
  no Spring SQL type appears in a public signature. Those decisions stay yours. Architecture tests
  ban the blocking, scheduling and rendering-type leaks, and every build diffs the public API
  through Revapi.
- **Deterministic output.** Regenerating over unchanged sources is byte-identical, and the shape is
  pinned by a committed corpus of expected files, so it cannot drift without a visible diff.
- **A closed algebra.** The query surface was derived from measured usage in real codebases and
  stops where full SQL begins. It does not wrap your repositories, replace your converters or ask
  you to adopt a query language. That boundary is what keeps it small enough to be correct.

## What it supports

**Generation**, by `entity-metamodel-processor`, a JSR-269 processor with no third-party dependency:

- `@Table` classes and records, following Spring's persistent-property rules;
  `-Aentitymetamodel.requireColumnAnnotation` opts into the stricter rule.
- Inherited members flattened into the concrete entity and re-anchored there; nested `@Table` types
  mirrored as nested metamodels.
- Exact declared types — generics, arrays, bounded wildcards — carried into the ref type arguments.
- Output held byte-for-byte against the committed corpus on JDK 17, 21 and 25.
- Registered *isolating*, and held so by a test, so an incremental build regenerates only the
  metamodels whose inputs changed.

**Queries**, by `entity-metamodel-runtime-r2dbc`, each item exercised against a real PostgreSQL 16
in the integration suite:

- Inner and left-outer joins, from a `JoinRef` or from an `ON` condition you state, binds included.
  One table can join several times under distinct instances, all hydrated from one row.
- Typed filters — equality, ranges, `IN`, `LIKE`, null tests, column-to-column comparison,
  negation — composed with `and`/`or` and parenthesised by construction, so an `OR` cannot widen a
  match by re-associating.
- Terminals `all`, `page`, `slice`, `list`, `one`, `first`, `count` and `exists`, every one a cold
  publisher. Every row-returning terminal takes a mapper, and `all` also takes a `Pageable` to
  stream one window as a `Flux`.
- Count elision: a page counts only where the rows in hand cannot imply the total, and `slice`
  never counts. It fetches one row past the page and reports a successor from whether it arrived.
- `distinct()`, for the row multiplication a to-many join causes; the total and the paging probe
  keep their meaning rather than being approximated.
- `Pageable` translation, including a sort property that arrives as text from a web request.
- A `Criteria` your application already builds, accepted unchanged through `CriteriaAdapter` with
  nested groups and SQL precedence intact, so adopting this is not a rewrite of your filter code. An
  integration test holds its rows equal to Spring's own template across a matrix of filters.
- Schema-qualified tables, round-tripped against a real non-default schema.
- Projections: `readProjection` takes a closed interface or a DTO, so a listing can carry two
  columns of a joined table without hydrating it.
- A raw-SQL door for what the vocabulary cannot say, where `{0}` names either a bind value or a
  property whose column the library writes. Numbered rather than `?`, so a fragment can still use
  PostgreSQL's jsonb `?`, `?|` and `?&` operators.

Two boundaries sit next to that list. The doors that take text — a `Criteria` and a `Pageable` sort —
resolve **any persisted property** of the entity, so an application forwarding a filter or sort term it
did not build is the component deciding what may be filtered and sorted on; the library does not
narrow it. And the library refuses only what it could not render at all: a condition nested deeper
than 32 levels fails with an argument error naming the depth and the limit — a long flat `and`/`or`
chain is not deep — while `IN`-list size and page size are not capped and an oversized `IN` fails as a
reported driver error. Reports about generated SQL, the raw door included, are in scope of
[`SECURITY.md`](SECURITY.md).

[`CHANGELOG.md`](CHANGELOG.md) carries the same list with the reasoning behind each rule, and
[`docs/query-recipes.md`](docs/query-recipes.md) has the answers given in place of a feature, keyset
pagination among them.

## What it deliberately doesn't do

- **No aggregate API.** No `GROUP BY`, `HAVING`, `UNION` or subquery vocabulary. "Parents that have
  a child" is an `EXISTS` fragment through the raw door, not a join; a join expands where a
  restriction should not.
- **Relationship members are not generated yet.** A property pointing at another aggregate is
  reported (`EM-N3`) and gets no constant, so joins are `JoinRef.of(source, target)` you write.
  Types the metamodel cannot express yet — embedded values, generic entity types — or cannot name
  from the generated class — a private nested type — are reported the same way (`EM-N6`) rather
  than dropped in silence.
- **Not JPA.** `jakarta.persistence` annotations are not read; the input is
  `org.springframework.data.relational.core.mapping`. Discovery also needs `@Table` itself. The
  compiler does not present types carrying a stereotype composed over it, though such a type *is*
  recognised once another entity refers to it.
- **Only part of Spring's read machinery applies,** because rows are materialised from their own
  columns rather than through an entity template. Property-level reading converters work everywhere;
  an entity-level `Converter<RowDocument, T>` applies to a whole-entity read but not to a
  projection, and an entity-level `Converter<Row, T>` and `AfterConvertCallback` never fire at all.
  Post-read logic belongs in the mapper the executor takes.
- **Projections are checked one way only.** An interface projection is checked against the entity
  and an open `@Value` one is refused; a DTO is bound by Spring itself and passed through unchecked,
  so a stale field there reads null. The first `readProjection` per projection type also pays a
  one-time cost: Spring's introspection reads the projection's class file from disk on the event
  loop, cached afterwards.
- **Schema resolution has sharp edges.** A `NamingStrategy` default is resolved once per entity and
  cached, so it cannot route per tenant; a schema that must vary belongs on `@Table` as SpEL. A
  dotted `@Table("a.b")` reads as schema `a`, table `b`, which silently targets the wrong relation
  if a schema `a` holds a table `b`; `@Table(value = "a.b", schema = "…")` turns that off. The split
  is this library's read path only. The CHANGELOG has the full rule set.
- **When a varying name is read follows the argument list.** A terminal taking a `Pageable`
  defers, so `page`, `slice` and the windowed `all` resolve a SpEL `@Table` name on subscribe and
  report a failure on the publisher; every other terminal resolves when you call it and throws from
  the call. A publisher described under one tenant and subscribed under another therefore reads
  different relations through the two groups.
- **One dialect is exercised end to end.** Rendering goes through Spring's own dialect machinery,
  but only PostgreSQL is tested.
- **Across a module boundary, Gradle will not regenerate.** Editing a mapped supertype in another
  module leaves the consuming module up to date, so its metamodels are not rewritten. The bound is
  that columns resolve at use: a renamed property throws from `PropertyRef.columnName` rather than
  querying the wrong column.
- **A release candidate, not GA.** The public signatures of `core`, `runtime` and `runtime-r2dbc`
  froze on 2026-09-06, and every build runs a compatibility gate over them against `2.0.0-RC1`:
  Revapi, plus architecture tests pinning the exact foreign types a public signature may carry. A
  break before `2.0.0` is possible only through a justified ledger entry, recorded in the CHANGELOG;
  fixes ship as the next candidate or as `2.0.0`, never as a patched RC1.

## Requirements and installation

- **Java 17+.** CI builds on 17, 21 and 25.
- **No Maven install required.** `./mvnw` pins the build to Maven 3.9.16. With your own Maven, the
  enforcer floor is 3.9.
- **Spring Data 4.0.x–4.1.x** (`spring-data-relational` / `spring-data-r2dbc`). Applications on the
  3.5.x line have to upgrade first.
- **Any build that runs javac.** There is no build-tool plugin to install.

Everything resolves from Maven Central. Building from source is for contributors:
`./mvnw -B verify` runs the unit tests and the integration suite, which executes its statements
against PostgreSQL in Testcontainers and is skipped when Docker is unavailable locally, never in CI.

### Maven

The two blocks at the top of [Five minutes](#five-minutes): the runtime as a dependency, the
processor under `annotationProcessorPaths`. To hold the family on one version, import the BOM and
drop the version from the runtime dependency; the processor path keeps its explicit version here.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.vadimbabich</groupId>
      <artifactId>entity-metamodel-bom</artifactId>
      <version>2.0.0-RC1</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

### Gradle

```kotlin
dependencies {
  implementation("io.github.vadimbabich:entity-metamodel-runtime-r2dbc:2.0.0-RC1")
  annotationProcessor("io.github.vadimbabich:entity-metamodel-processor:2.0.0-RC1")
}
```

For the metamodel and its typed references without query execution, depend on
`entity-metamodel-runtime` instead.

## IDE

Generated sources go through the standard `Filer`, so any IDE that runs annotation processing picks
them up. IntelliJ IDEA needs *Enable annotation processing*, or a build delegated to Maven or
Gradle. Eclipse and other JDT-based tooling use their own processing support; the processor
generates during rounds rather than only in the final one, which is what Eclipse needs.

The IDE matrix has not been run yet, so treat IDE behaviour as expected rather than verified. A
delegated Maven or Gradle build is the reliable path today.

## Troubleshooting

| Problem | Likely cause | Fix |
|---|---|---|
| No `X__` classes anywhere | processor not on the annotation-processor path, or the type has no `@Table` | check `annotationProcessorPaths` / `annotationProcessor`; only `@Table` types get a metamodel |
| `warning: No processor claimed any of these annotations` | `-Xlint:processing`; `@Table` is Spring's, and claiming it would take it from every other processor | `-Xlint:-processing`, or drop `-Werror` for that module |
| One property has no constant | it refers to another aggregate (`EM-N3`), or its type cannot be expressed yet or named from the generated class (`EM-N6`) | the build log carries the note; use a hand-written `JoinRef` or the raw door |
| `EM-E6: … already exists` | a hand-written class collides with the generated name, or a stale generated file is an input | rename the type, or run a clean build |
| `EM-E8: … rename the entity` | the entity's simple name contains `__` or ends with `_`; `EntityRef` refuses both as an alias, so the metamodel would fail when it loads | rename the entity |
| An entity hydrates with a null field whose value is in the row | renderer and converter are behind two different mapping contexts | share one context, as in the executor bean above |
| A `Pageable` sort is rejected | the sort names a property the entity does not persist | it arrives as an error signal on the publisher, not a thrown exception; map it to a bad request |
| A `Criteria` is refused, naming a property | it used a column name; a column name can be another property's name, so resolving it would filter the wrong column | use the property name the message gives |
| A filter is refused, naming a depth and a limit | the condition nests deeper than the 32 levels the library renders; a flat `and`/`or` chain of any length is not deep | flatten or split the filter; `page`, `slice` and the windowed `all` deliver this on the publisher, every other terminal throws it |

## Where it's going

`2.0.0-RC1` is the first release, and it went out whole — a version that generates a metamodel,
builds a query and executes it, because Central is permanent and a milestone of parts would have
been permanent too. What stands between the candidate and `2.0.0` is a soak. The
compatibility gate already runs against the published candidate, and the marker contract — `@RawSql`
on the raw doors, both markers' retention — is held by the API ledger as well as by tests.
[`ROADMAP.md`](ROADMAP.md) has the order.

Each release is signed, carries a CycloneDX SBOM and attests its build provenance, so a jar from
Central can be checked against the exact workflow run and commit that built it:
`gh attestation verify <jar> --repo VadimBabich/entity-metamodel`. It also rebuilds byte-for-byte
from its tag on the release toolchain — Linux, Temurin 21, Maven 3.9.16 — with the version and the
commit-time `project.build.outputTimestamp` the published parent POM records.

The 1.x Maven plugin that preceded this one was retired and removed from the repository on
2026-08-30; it survives in git history and in its own tags.

## Documentation

- [`CHANGELOG.md`](CHANGELOG.md) — what exists, in detail; the record everything else condenses
  from.
- [`ROADMAP.md`](ROADMAP.md) — where it is going, and what gates the order.
- [`docs/query-recipes.md`](docs/query-recipes.md) — keyset pagination and the other answers given
  in place of a feature, with the SQL each one renders.
- [`docs/reactive-code-style.md`](docs/reactive-code-style.md) — the reactive rules; contractual for
  anything touching the runtime.
- [`docs/runbooks/golden-corpus-update.md`](docs/runbooks/golden-corpus-update.md) — how generated
  output is allowed to change.
- The corpus itself is the most precise description of the generated shape:
  `entity-metamodel-processor/src/test/resources/contract-corpus/`.

## Contributing

[`CONTRIBUTING.md`](CONTRIBUTING.md) covers the build and the conventions. Security reports go
through GitHub's private advisories, per [`SECURITY.md`](SECURITY.md); please do not open a public
issue for those.

Bug reports are welcome, use-case reports more so, and there is an issue template for each. The
design came from measured usage in real codebases, and that only keeps working if people describe
what they are actually doing.
