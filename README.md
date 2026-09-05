# entity-metamodel

Generate a metamodel from your Spring Data entities while they compile, then build R2DBC
queries — joins included — that the compiler checks.

> **Nothing is on Maven Central yet.** There is no artifact there to depend on: you build it from
> source, and the public API can still change. Everything below runs today and is held by tests on
> every build. [Where it's going](#where-its-going) has the bar for a first release.

## The problem

Spring Data R2DBC takes property and column names as strings:

```text
Criteria.where("ownerEmail").is(email);
```

The compiler cannot see that string. Rename the field, change the `@Column`, delete the property —
the build stays green and you find out from a failing query at runtime, usually on the path nobody
exercised locally. Grep is the only refactoring tool that works, and it misses the name that was
assembled by concatenation.

The second cost is joins. Spring's SQL DSL can express them, but the fluent `Query`/`Criteria` API
has no join vocabulary, so every repository touching two tables hand-assembles the same ceremony:
aliased table handles, comparisons built from string lookups, bind markers threaded through `ON`
clauses, per-entity column lists, manual row de-multiplexing. Codebases grow an in-house layer whose
whole job is carrying that state around.

Both have one fix: derive the references from the entities during the build, and give joins a
vocabulary that carries types. A rename then breaks compilation, which is where you want to hear
about it.

## What it does differently

Hand-written constant classes solve the first half and rot quietly — nothing keeps them in step with
the entity. Runtime introspection stays in step but moves the error back to runtime, which is the
problem you started with.

This participates in compilation instead:

- **A wrong reference is a compile error.** `PropertyRef<Account, String>` carries the entity, the
  property name *and* the value type, so comparing against the wrong type, or joining unrelated
  columns, does not compile.
- **Entity-first, one mapping context.** No SQL name is ever baked into generated code. Names
  resolve at use through Spring's own `RelationalMappingContext`, so `@Column` and your
  `NamingStrategy` stay the single source of truth for generation, rendering and row reading alike.
- **Ordinary code.** Generated files are plain `.java` on your compile path. Ctrl-click reaches the
  declaration, find-usages works, the debugger steps through them.
- **Deterministic.** Regenerating over unchanged sources is byte-identical — no dates, no
  environment-dependent content — and the output is pinned by a committed corpus of expected files,
  so it cannot drift without a visible diff.

## Five minutes

Two ordinary Spring Data entities, with nothing added for the tool's benefit:

```java
@Table("accounts")
public class Account {
  @Id @Column("account_id") Long id;
  @Column("owner_email") String ownerEmail;
}

@Table("memberships")
public class Membership {
  @Id @Column("membership_id") Long id;
  @Column("account_id") Long accountId;
}
```

Compile, and a metamodel appears beside each one:

```java
@Generated("io.github.vadimbabich.entitymetamodel")
public final class Account__ {
  public static final EntityRef<Account> ENTITY = EntityRef.of(Account.class);

  public static final PropertyRef<Account, Long> ID = ENTITY.property("id", Long.class);
  public static final PropertyRef<Account, String> OWNER_EMAIL = ENTITY.property("ownerEmail", String.class);
}
```

Now the query. `JoinRef.of` compiles only because both sides are `Long`, and `is("…")` only because
`OWNER_EMAIL` is a `String` property:

```java
JoinRef<Membership, Account> owner = JoinRef.of(Membership__.ACCOUNT_ID, Account__.ID);

FluentSelect<Membership> byOwner = FluentSelect.from(Membership__.ENTITY)
    .join(owner)
    .where(Account__.OWNER_EMAIL.is("owner@example.com"))
    .orderBy(Membership__.ID.desc());

Flux<Membership> rows = executor.all(byOwner);
Mono<Page<Membership>> page = executor.page(byOwner, PageRequest.of(0, 20));
```

`byOwner` is an immutable value that performs no I/O — describing it touches no database, and the
same description serves both the page and its count. Rename `ownerEmail` and this file stops
compiling.

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

Renderer and converter must share one mapping context: one names a column when projecting it and the
other when reading it back, so two contexts produce rows nothing claims.

## Core ideas

- **Compile-time, because the input is compilation.** Annotated Java in the same module is exactly
  the JSR-269 case. Participating in the compile buys resolved types, inheritance, incremental
  builds and Maven + Gradle + IDE support from one artifact — no build-tool plugin to maintain.
- **A description is a value.** `FluentSelect` is immutable and performs no I/O; rendering it is a
  pure function. The library never schedules, times out, retries, caches or opens a transaction, and
  no Spring SQL type appears in a public signature — those decisions stay yours.
- **A closed algebra.** The query surface was derived from measured usage in real codebases and
  stops where full SQL begins. It does not wrap your repositories, replace your converters or ask
  you to adopt a query language. That boundary is what keeps it small enough to be correct.

## What it supports

**Generation** — `entity-metamodel-processor`, a JSR-269 processor with no third-party dependency:

- `@Table` classes and records, following Spring's persistent-property rules: `@Column` renames but
  does not decide membership, `@Transient` wins. `-Aentitymetamodel.requireColumnAnnotation` opts
  into the stricter rule.
- Inherited members flattened into the concrete entity and re-anchored there; nested `@Table` types
  mirrored as nested metamodels.
- Exact declared types — generics, arrays, bounded wildcards — carried into the ref type arguments.
- Members ordered by name, so output does not depend on the compiler. Held byte-for-byte against the
  committed corpus on JDK 17, 21 and 25.
- Registered *isolating*: editing one entity regenerates one metamodel. Measured end to end on
  Gradle 9.4.1, including a rename of a private field on a mapped supertype.

**Queries** — `entity-metamodel-runtime-r2dbc`, every item below exercised against a real PostgreSQL
16 in the integration suite:

- Inner and left-outer joins, from a `JoinRef` or from an `ON` condition you state, binds included.
  One table can join several times under distinct instances, all hydrated from one row.
- Typed filters — equality, ranges, `IN`, `LIKE`, null tests, column-to-column comparison,
  negation — composed with `and`/`or` and parenthesised by construction, so an `OR` cannot widen a
  match by re-associating.
- `distinct()`, for the row multiplication a to-many join causes. The total and the paging probe
  keep their meaning rather than being approximated.
- `page`, `slice`, `list`, `one`, `first`, `count` and `exists`, all returning cold publishers.
- Count elision: a page counts only where the rows in hand cannot imply the total. `slice` never
  counts at all — it fetches one row past the page and reports a successor from whether that row
  arrived, which is what an infinite-scrolling listing actually needs.
- `Pageable` translation, including a sort property that arrives as text from a web request.
- A `Criteria` your application already builds, accepted unchanged through `CriteriaAdapter` with
  nested groups and SQL precedence intact — so adopting this is not a rewrite of your filter code.
  An integration test holds its rows equal to Spring's own template across a matrix of filters.
- Schema-qualified tables: an entity that declares a schema resolves against it rather than against
  whatever `search_path` offers, round-tripped against a real non-default schema.
- Projections: `readProjection` takes a closed interface or a DTO, so a listing can carry two
  columns of a joined table. `page`, `slice` and `all` all take a mapper, so a projected listing
  needs no hand-assembly.
- A raw-SQL door for what the vocabulary cannot say, where `{0}` names either a bind value or a
  property whose column the library writes. Numbered rather than `?`, so a fragment can still use
  PostgreSQL's jsonb `?`, `?|` and `?&` operators.

[`CHANGELOG.md`](CHANGELOG.md) carries the same list in detail, with the reasoning behind each rule.

## What it deliberately doesn't do

- **No aggregate API.** No `GROUP BY`, `HAVING`, `UNION` or subquery vocabulary. "Parents that have
  a child" is an `EXISTS` fragment through the raw door, not a join — a join expands where a
  restriction should not.
- **Relationship members are not generated yet.** A property pointing at another aggregate is
  reported (`EM-N3`) and gets no constant, so joins are `JoinRef.of(source, target)` you write.
  Types the metamodel cannot express yet — embedded values, generic entity types — are reported the
  same way (`EM-N6`) rather than dropped in silence.
- **Not JPA.** `jakarta.persistence` annotations are not read; the input is
  `org.springframework.data.relational.core.mapping`. Discovery also needs `@Table` itself — the
  compiler does not present types carrying a stereotype composed over it, though such a type *is*
  recognised once another entity refers to it.
- **Only part of Spring's read machinery applies,** because rows are materialised from their own
  columns rather than through an entity template. Property-level reading converters work everywhere.
  An entity-level `Converter<RowDocument, T>` applies to a whole-entity read but not to a
  projection, and projecting such an entity is refused rather than quietly bypassing it — a
  deliberate difference from Spring's template, where the same projection succeeds and hands back
  the unconverted value. An entity-level `Converter<Row, T>` and `AfterConvertCallback` never fire
  at all. Post-read logic belongs in the mapper the executor takes.
- **Projections are checked one way only.** An interface projection is checked against the entity
  and an open `@Value` one is refused; a DTO is bound by Spring itself and passed through unchecked,
  so a stale field there reads null.
- **Schema resolution has sharp edges.** A schema reaches the SQL from the `@Table` attribute or a
  `NamingStrategy` default, so an entity declaring none can still render qualified and a
  `search_path`-routed deployment finds those queries pinned. That default is resolved once per
  entity and cached, so it cannot route per tenant — a schema that must vary belongs on `@Table` as
  SpEL. A dotted `@Table("a.b")` reads as schema `a`, table `b`, which silently targets the wrong
  relation if a schema `a` holds a table `b`; `@Table(value = "a.b", schema = "…")` turns that off,
  at the price of a schema fixed in source. The split is this library's read path only, so an
  application that also writes through a repository should state `schema`. CHANGELOG has the full
  rule set.
- **A name that varies is read when you call the terminal, not when something subscribes.** A SpEL
  `@Table` value or schema resolves while the publisher is described, so one built under a tenant
  and subscribed under another queries the first, and a failure to resolve throws from every
  terminal except `page` and `slice`. Resolving costs two evaluations, and `page` pays twice over.
- **One dialect is exercised end to end.** Rendering goes through Spring's own dialect machinery,
  but only PostgreSQL is tested.
- **Across a module boundary, Gradle will not regenerate.** Editing a mapped supertype in another
  module leaves the consuming module up to date, so its metamodels are not rewritten. The bound is
  that columns resolve at use: a renamed property throws from `PropertyRef.columnName` rather than
  querying the wrong column.
- **No compatibility promise yet.** Signatures can still change until the API freeze.

## Compared with the alternatives

Every option here solves a real problem. The question is whose, and what it couples you to.

| | Hand-written constants | Spring Data alone | QueryDSL | jOOQ | entity-metamodel |
|---|---|---|---|---|---|
| Rename-safe references | no | no | yes | yes | **yes** |
| Typed joins | n/a | no | yes (JDBC) | yes | **yes** |
| Reactive R2DBC execution | n/a | yes | no first-party | yes | **yes** |
| Metamodel derived from | you | — | entities | database schema | **your entities** |
| Shares Spring Data's mapping context | yes | yes | no | no | **yes** |
| Full SQL: aggregates, unions, windows, DML | — | no | yes | **yes** | no — deliberate |

Two differences do most of the work. QueryDSL's SQL module executes over JDBC, so a reactive
codebase gets no first-party executor; upstream has also been quiet since 5.1.0 in January 2024,
with maintenance continuing in a community fork. jOOQ is the strongest alternative and is
schema-first: its metamodel comes from the database, so alongside Spring Data your column names,
converters and entity mapping live in two systems that nothing keeps consistent. If your queries
need aggregation, set operations or window functions as a matter of course, jOOQ is the right tool —
and the two coexist without conflict, precisely because this one stops where full SQL begins.

## Query recipes

The answers this library gives in place of a feature. The SQL each one renders is pinned by
`DocumentedRecipeRenderingTest`, `PageableTranslationTest` and `SortAndPaginationRenderingTest`, and
the keyset traversals are walked against PostgreSQL in the integration suite, so a published recipe
cannot quietly stop working.

**Is there another page, without counting.** Where you are fetching the page anyway, `slice` is the
answer — it asks for one row beyond the page and issues no count statement at all:

```java
Mono<Slice<Membership>> slice = executor.slice(byOwner, PageRequest.of(0, 20));
```

Where you are *not* fetching the page — a "load more" control that only needs enabling — the probe
is cheaper still, because the description's own offset is honoured. The offset is the end of the
page you are showing, so for the first page of twenty it is twenty:

```java
Mono<Boolean> more = executor.exists(byOwner.offset(20));
```

**Keyset (cursor) pagination.** There is no keyset terminal, and both predicate forms are
expressible today with no library change. The metamodel supplies the qualified column names either
way, so neither spells out a table alias and neither carries an injection exposure.

Prefer the row-value form wherever the sort runs one direction throughout — it is the only one of
the two that PostgreSQL turns into an index seek. **The comparison inverts with the sort:** `>`
walks an all-ascending sort, `<` an all-descending one.

```java
// ORDER BY owner_email ASC, account_id ASC
Condition after =
    SqlExpr.raw("({0}, {1}) > ({2}, {3})", Account__.OWNER_EMAIL, Account__.ID,
        cursorEmail, cursorId);

// ORDER BY owner_email DESC, account_id DESC — the feed case
Condition after =
    SqlExpr.raw("({0}, {1}) < ({2}, {3})", Account__.OWNER_EMAIL, Account__.ID,
        cursorEmail, cursorId);
```

Getting that operator wrong is silent: the SQL is valid, the plan still seeks, and the traversal
re-serves the leading page for ever.

The expanded form is the fallback for a mixed-direction sort, which the row-value form cannot
express. It is correct and portable, and it is **not** free:

```java
Condition after = Account__.OWNER_EMAIL.gt(cursorEmail)
    .or(Account__.OWNER_EMAIL.is(cursorEmail).and(Account__.ID.gt(cursorId)));
```

Both parenthesise correctly with a filter of your own on either side, and both traverse correctly —
an integration test walks a tied leading key with each.

One `EXPLAIN (ANALYZE, BUFFERS)` per form, on synthetic data you can rebuild: PostgreSQL 16, 200,000
rows, ten rows per distinct leading key so that key is non-unique, a composite index on the two key
columns, `ANALYZE`, page size 20, cursor at the 90th percentile **of the index order**.

| | row-value | expanded |
|---|---|---|
| how the planner uses the predicate | `Index Cond` — seeks to the cursor | `Filter` — scans from the start and discards |
| rows discarded to fill one page | 0 | 180,001 |
| buffers | 5 | 6,190 |
| execution | 0.05 ms | 14.47 ms |

The gap grows with depth — a sweep over the same data timed the expanded form at 1.9 / 9.3 / 16.1 ms
at the 10th, 50th and 90th percentile, while the row-value form stayed flat at ~0.03 ms. **The
expanded form uses the index for ordering only, so it reproduces the deep-page degradation keyset
pagination exists to remove.** The timings are point-in-time and the build does not re-check them;
the plan shapes are the durable part. If your sort is mixed-direction and your pages go deep,
reversing the whole sort to make it uniform is usually the better trade.

What the library cannot check here, and you must:

- **The sort has to end in a unique key** — the entity's `@Id` is the usual one. Without it the
  traversal skips or repeats rows, and no error says so.
- **Every sort property must be projected**, and the key values must match the sort in arity, order
  and type.
- **No null sort keys.** Both forms evaluate to NULL where a key is NULL, so those rows drop out of
  the traversal silently.
- **The row-value form needs one direction throughout, and its operator has to match that
  direction.** A mixed-direction sort such as `createdAt DESC, id ASC` is what neither operator can
  express, so that one needs the expanded form — at the cost measured above.
- **Not over a to-many join,** where one root id no longer identifies one row. Add `distinct()` and
  the joined instance's id to the key, or key on a description that does not join.

## Requirements and installation

- **Java 17+.** CI builds on 17, 21 and 25.
- **Maven 3.9+** to build from source. The enforcer fails the build below it.
- **Spring Data 4.0.x–4.1.x** (`spring-data-relational` / `spring-data-r2dbc`). Applications on the
  3.5.x line have to upgrade first.
- **Any build that runs javac.** There is no build-tool plugin to install.

Nothing is on Maven Central, so install it into your local repository first:

```bash
git clone https://github.com/VadimBabich/entity-metamodel.git
cd entity-metamodel
mvn -B install
```

`mvn -B verify` additionally runs the integration tests: the query suite executes its statements
against PostgreSQL in Testcontainers (skipped with a notice when Docker is unavailable locally).

### Maven

The processor goes on the annotation-processor path and never reaches your runtime classpath. The
runtime goes on the compile path, because generated code imports it.

```xml
<dependency>
  <groupId>io.github.vadimbabich</groupId>
  <artifactId>entity-metamodel-runtime-r2dbc</artifactId>
  <version>2.0.0-SNAPSHOT</version>
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
        <version>2.0.0-SNAPSHOT</version>
      </path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

### Gradle

```kotlin
repositories {
  mavenLocal()
  mavenCentral()
}

dependencies {
  implementation("io.github.vadimbabich:entity-metamodel-runtime-r2dbc:2.0.0-SNAPSHOT")
  annotationProcessor("io.github.vadimbabich:entity-metamodel-processor:2.0.0-SNAPSHOT")
}
```

For the metamodel and its typed references without query execution, depend on
`entity-metamodel-runtime` instead. `entity-metamodel-bom` aligns the whole family on one version.

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
| One property has no constant | it refers to another aggregate (`EM-N3`), or its type cannot be expressed yet (`EM-N6`) | the build log carries the note; use a hand-written `JoinRef` or the raw door |
| `EM-E6: … already exists` | a hand-written class collides with the generated name, or a stale generated file is an input | rename the type, or run a clean build |
| An entity hydrates with a null field whose value is in the row | renderer and converter are behind two different mapping contexts | share one context, as in the executor bean above |
| A `Pageable` sort is rejected | the sort names a property the entity does not persist | it arrives as an error signal on the publisher, not a thrown exception — map it to a bad request |
| A `Criteria` is refused, naming a property | it used a column name; a column name can be another property's name, so resolving it would filter the wrong column | use the property name the message gives |

## Where it's going

**Nothing goes to Maven Central until it is whole.** Maven Central is permanent, so the first
release will be a version you can run end to end — generate a metamodel, build a query, execute it —
rather than a milestone of parts. What stands between here and that release is freeze and packaging
work rather than core function; API compatibility gating is the next station.

When it does ship, each release is cut by a dispatch-only workflow that signs the artifacts,
attaches their CycloneDX SBOMs to the GitHub release, and attests build provenance — so a jar
obtained from Maven Central can be checked against the exact workflow run and commit that built it:
`gh attestation verify <jar> --repo VadimBabich/entity-metamodel`.

The 1.x Maven plugin that preceded this one was retired and removed from the repository on
2026-08-30; it survives in git history and in its own tags. The generated shape it produced is not
the shape produced now — metamodels no longer land in packages this project does not own — which was
the point of the reboot.

## Documentation

- [`CHANGELOG.md`](CHANGELOG.md) — what exists, in detail. The record the rest condenses from.
- [`ROADMAP.md`](ROADMAP.md) — where it is going, and what gates the order.
- [`docs/reactive-code-style.md`](docs/reactive-code-style.md) — the reactive rules; contractual for
  anything touching the runtime.
- [`docs/runbooks/golden-corpus-update.md`](docs/runbooks/golden-corpus-update.md) — how generated
  output is allowed to change.
- The corpus is the most precise description of the generated shape there is:
  `entity-metamodel-processor/src/test/resources/contract-corpus/`.

## Contributing

[`CONTRIBUTING.md`](CONTRIBUTING.md) covers the build and the conventions. Security reports go
through GitHub's private advisories, per [`SECURITY.md`](SECURITY.md) — please do not open a public
issue for those.

Bug reports are welcome; use-case reports more so. The design came from measured usage in real
codebases rather than from guesswork, and that only keeps working if people describe what they are
actually doing.
