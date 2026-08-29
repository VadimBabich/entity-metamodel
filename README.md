# entity-metamodel

Generated entity metamodels for Spring Data R2DBC, so your queries stop referring to columns by
string.

## What's here

The repository holds two lines at different stages. They release independently.

| | What it is | Where to get it | State |
|---|---|---|---|
| **`jpa-metadata-maven-plugin`** | Maven plugin that parses your entity sources and generates metamodels. The working generator. | [build from source](#building-from-source) | 1.x, in use |
| **`entity-metamodel-core`**, **`-runtime`**, **`-runtime-r2dbc`**, **`-processor`**, **`-bom`** | The replacement: an annotation processor, the reference types it generates against, and a typed query builder that executes over Spring Data R2DBC. | [in this repository](#the-2x-family) | generates and executes, unpublished |

If you want type-safe column references working in your project today, you want the plugin, and the
[five-minute walkthrough](#five-minutes). If you're here to see where this is going, start with
[the 2.x family](#the-2x-family) — it is further along than you might expect.

## The problem

Spring Data R2DBC gives you `Criteria` and a SQL DSL, and both take column names as strings:

```text
Criteria.where("usat_value").is(value);
```

That string is invisible to the compiler. Rename the field, change the `@Column`, delete the
property — the build stays green and you find out from a failing query at runtime, usually in the one
code path nobody exercised locally. Grep is the only refactoring tool that works, and it misses the
string that was built by concatenation.

The fix is unglamorous: derive the names from the entities during the build and refer to those
instead. Renaming a property then breaks compilation, which is where you want to hear about it.

## What makes it different

The alternatives tend to sit at one of two extremes. Hand-written constant classes work but rot
quietly — nothing keeps them in step with the entity. Runtime introspection stays in step but moves
the error back to runtime, which is the problem you were trying to solve.

This generates real Java source during the build:

- **Compile-time.** A wrong reference is a compile error, not a failed query.
- **Ordinary code.** Generated files are plain `.java` on your compile path. Ctrl-click reaches the
  declaration, "find usages" works, the debugger steps through them.
- **Deterministic.** Regenerating over unchanged sources is byte-identical — no timestamps, no
  environment-dependent content. A unit test and an integration test hold that still, so generated
  sources never churn your diffs.
- **Narrow.** It reads Spring Data Relational annotations and emits references. It does not wrap
  your repositories or ask you to adopt a query language; the 2.x query builder is there when you
  want it and ignorable when you don't.

## Five minutes

Install the plugin into your local repository first — see [Building from source](#building-from-source).

An entity — an ordinary Spring Data Relational record, with nothing added for the plugin's benefit:

```java
@Table("users")
public record User(
    @Id @Column("user_id") String id,
    @Column("user_name") String name
) { }
```

Run `mvn generate-sources`, and a metamodel appears beside it:

```java
public final class User_ {
  public static final Column_ ID = new Column_(User.class, "id");
  public static final Column_ NAME = new Column_(User.class, "name");

  public static Table getTable() {
    return StaticR2dbcEntityTemplateAccessor_.getTable(User.class);
  }
}
```

Queries now name the property through a constant and the table through the entity:

```java
Criteria criteria = Criteria.where(UserAttribute_.ATTRIBUTE_VALUE.name()).is(attributeValue);

SelectJoin select = Select.builder()
    .select(AsteriskFromTable.create(User_.getTable()))
    .from(User_.getTable())
    .join(UserAttribute_.getTable())
    .on(Conditions.isEqual(UserAttribute_.USER_ID, User_.ID));
```

`Column_` holds the entity type and the property name, and resolves the physical column through
Spring's own mapping context — so `@Column("user_id")` stays the single source of truth. Rename `id`
to `userId` and this file stops compiling.

## Configuration

```xml
<plugin>
  <groupId>io.github.vadimbabich</groupId>
  <artifactId>jpa-metadata-maven-plugin</artifactId>
  <version>1.1.0-SNAPSHOT</version>
  <executions>
    <execution>
      <goals><goal>generate-metadata</goal></goals>
    </execution>
  </executions>
  <configuration>
    <packageName>com.example.model</packageName>
  </configuration>
</plugin>
```

The goal binds to `generate-sources` and registers its output as a compile source root, so generated
code is compiled with everything else.

One extra step: `getTable()` goes through a generated accessor, which has to be a bean.

```java
@Bean
StaticR2dbcEntityTemplateAccessor_ staticAccessor() {
  return new StaticR2dbcEntityTemplateAccessor_();
}
```

## Parameters

| Parameter               | Required | Default                                                | Description                                   |
|-------------------------|----------|--------------------------------------------------------|-----------------------------------------------|
| packageName             | ✅        | none                                                   | Root package scanned for entities.            |
| outputDirectory         | ❌        | ${project.build.directory}/generated-sources/metamodel | Where generated sources are written.          |
| sourceDirectory         | ❌        | src/main/java                                          | Source root to scan, relative to the project. |
| languageLevel           | ❌        | JAVA_17                                                | Java level used to parse the sources.         |
| entityMetadataGenerator | ❌        | r2dbc                                                  | Generator implementation; only `r2dbc` ships. |

A test checks this table against the Mojo's own parameters, so it cannot drift from the code.

## Supported

- Spring Data Relational `@Table` and `@Column`, on records and on classes
- Nested entity types, mirroring your source nesting
- Java 17+, Maven 3.9+
- Configurable source root, output directory and parse level

## Not supported

Worth reading before you adopt it.

- **Not JPA.** Despite the artifactId, `jakarta.persistence` annotations are ignored — it reads
  `org.springframework.data.relational.core.mapping` only. The name is historical, and the artifact
  keeps it for its remaining releases.
- **Maven only.** There is no Gradle plugin. (The 2.x processor removes this limit.)
- **One package.** Only classes under `packageName` are scanned. Not dependencies, not sibling
  packages.
- **Source-based.** It parses `src/main/java`, so entities that arrive in a jar are invisible to it.
- **Two files land in Spring's own packages** (`Column_`, `StaticR2dbcEntityTemplateAccessor_`).
  That works and is frozen for compatibility, but it is the main thing 2.x removes.
- **Queries are not fully type-safe.** `Criteria` takes `Object` for values, so this buys correct
  *names*, not checked comparisons. (That is the other thing 2.x removes.)

## IDE

Generated sources are registered as a compile source root, so any IDE that imports the Maven model
picks them up after a reimport. If IntelliJ hasn't, right-click
`target/generated-sources/metamodel` → **Mark Directory as → Generated Sources Root**.

## Troubleshooting

| Problem | Likely cause | Fix |
|---|---|---|
| Nothing generated | `packageName` doesn't match the entity package, or entities sit outside `sourceDirectory` | Check both against the summary the plugin logs |
| Plugin not found | it isn't published | `mvn install` it from source first |
| `Column_` won't resolve | generated output isn't on the compile path | Reimport the Maven project; confirm the goal ran in `generate-sources` |
| `getTable()` fails at runtime | the accessor isn't a bean | Register `StaticR2dbcEntityTemplateAccessor_` (above) |
| One entity is missing | no `@Table`, or it is outside `packageName` | Only `@Table` types in that package are emitted |
| Stale metamodel after a rename | output kept from an earlier run | `mvn clean generate-sources` |
| Parse failure on newer syntax | source uses a level above `languageLevel` | Raise `languageLevel` |

## The 2.x family

The replacement is built in this repository and not published. It now generates *and* executes;
what is missing before a first release is packaging and freeze work, not core function. Worth
reading if you want to see the shape before it ships — not something to depend on yet.

**Generation.** The annotation processor (`entity-metamodel-processor`) replaces source parsing
with JSR-269: build-tool neutral, incremental-aware — it is registered in the *isolating* category,
so editing one entity regenerates one metamodel — and it emits into your packages only. From the
`Account` entity in its test corpus it generates:

```java
public final class Account__ {
  public static final EntityRef<Account> ENTITY = EntityRef.of(Account.class);

  public static final PropertyRef<Account, Long> ID = ENTITY.property("id", Long.class);
  public static final PropertyRef<Account, String> OWNER_EMAIL = ENTITY.property("ownerEmail", String.class);
  // …one constant per persistent property, inherited members flattened in
}
```

A `PropertyRef<Account, Long>` carries the entity, the property name *and the value type* — which
is what makes comparisons checkable, not just names. Physical names still resolve through Spring's
mapping context at use, so `@Column` stays the single source of truth. Output is held byte-for-byte
against a committed corpus of golden files on every build.

**Execution.** `entity-metamodel-runtime-r2dbc` runs queries described with those references. A
description is an immutable value that performs no I/O; one executor the consumer wires themselves
turns it into SQL and cold publishers:

```java
JoinRef<Membership, Account> owner =
    JoinRef.of(Membership__.ACCOUNT_ID, Account__.ID);   // compiles only if the value types match

FluentSelect<Membership> byOwner =
    FluentSelect.from(Membership__.ENTITY)
        .join(owner)
        .where(Account__.OWNER_EMAIL.is("owner@example.com"))
        .orderBy(Membership__.ID.desc());

Flux<Membership> rows = executor.all(byOwner);
Mono<Page<Membership>> page = executor.page(byOwner, pageRequest);
```

What that covers today, each part exercised against a real PostgreSQL 16 in the integration suite:

- Inner and left-outer joins from declared relationships or from a condition you state, including
  the same table joined several times under distinct aliases — and both instances hydrated from a
  single row. A join to a to-many side multiplies rows; `distinct()` collapses a listing — and its
  total, and its paging probes — back to distinct entities.
- A typed filter vocabulary — equality, ranges, `IN`, `LIKE`, null tests, column-to-column
  comparison, negation — composed with `and`/`or` and parenthesised by construction, so an `OR`
  cannot silently widen a match.
- A `Criteria` your application already builds is accepted with its meaning intact — nested groups,
  and a chain folded under SQL precedence rather than left to right; an integration test holds the
  returned rows equal to Spring's own template across a matrix of filter shapes. Its references name
  properties, not columns; a column name is refused, naming the property to use instead.
- `Pageable` translation: the request's sort arrives as text, resolves against the entity, and an
  unknown property becomes an error signal on the publisher — request data, not a thrown exception.
- `page`, `list`, `one`, `first`, `count` and `exists` terminals returning cold publishers. The
  library never schedules, times out, retries or opens a transaction; that stays yours.
- A raw-SQL door for what the vocabulary cannot say, where `{0}` names either a bind value or a
  property whose column the library writes itself. Numbered rather than `?`, so a fragment can use
  the dialect's own question-mark operators — PostgreSQL's jsonb `?`, `?|`, `?&` — which an
  execution test exercises against a real database.
- Where a projected label would pass the 63 bytes PostgreSQL silently truncates identifiers to, the
  statement switches to positional table aliases instead of returning silently wrong rows.

Deliberate boundaries, stated up front: there is no aggregate API — no `GROUP BY`, no `HAVING`,
no `UNION`, no subquery vocabulary. "Parents that have a child" is an `EXISTS` fragment through the
raw door, not a join, because a join expands where a restriction should not. Signatures can still
change; there is no compatibility promise until the first release.

## Compared with the alternatives

Every option below solves a real problem. The honest comparison is about whose problem — and what
each choice couples your code to.

**Hand-written constant classes.** The zero-dependency answer, and the one most codebases start
with: a helper that returns column names, or a class of string constants next to each entity.
It works until it rots — nothing keeps it in step with the entity, a rename leaves the constant
compiling and the query broken, and every join you write by hand re-derives the same aliases.
This project is that idea with the rot removed: the build derives the references, so drift is a
compile error.

**Spring Data R2DBC alone.** Already on your classpath, and its `Criteria`/template API is good at
what it covers. Two structural limits: every property is a string the compiler cannot see, and the
fluent `Query`/`Criteria` API has no join vocabulary — the moment two tables are involved you drop
to hand-assembled SQL AST or a `@Query` string. This library keeps the stack (same mapping context,
same converters, same `DatabaseClient`) and adds the two missing pieces: compile-checked references
and typed joins. A `Criteria` you already build is accepted as-is, so adopting this is not a
rewrite of the filter code you have.

**QueryDSL.** The classic typed-query answer for JPA. For this stack it is a poor fit on two hard
facts: its SQL module executes over JDBC — there is no first-party reactive/R2DBC execution — and
the original project has been quiet since 5.1.0 (January 2024), with active maintenance continuing
in a community fork. Betting a reactive codebase on a blocking executor with an uncertain upstream
is the kind of decision this comparison exists to prevent.

**jOOQ.** The strongest alternative, and for some teams the right one: full SQL coverage —
subqueries, unions, window functions, DML — per-dialect rendering, and first-class R2DBC support.
The costs are structural rather than qualitative. jOOQ is schema-first: its metamodel is generated
from the database, so when you also run Spring Data your column names, converters and entity
mapping live in two systems that nothing keeps consistent — this library is entity-first and shares
Spring Data's one mapping context, so `@Column` and your registered converters stay the single
source of truth for generation, rendering and row reading alike. jOOQ brings its own execution and
row-mapping model rather than plugging into Spring Data's; it is dual-licensed, free for
open-source databases with commercial editions for commercial ones; and its code generation wants a
database (or DDL) at build time, where an annotation processor needs only your sources.

| | Hand-written | Spring Data alone | QueryDSL | jOOQ | entity-metamodel |
|---|---|---|---|---|---|
| Rename-safe references | no | no | yes | yes | **yes** |
| Typed joins | n/a | no | yes (JDBC) | yes | **yes** |
| Reactive (R2DBC) execution | n/a | yes | no first-party | yes | **yes** |
| Metamodel source | you | — | entities/schema | database schema | **your entities** |
| Shares Spring Data's mapping context | yes | yes | no | no | **yes** |
| Existing `Criteria` accepted unchanged | — | yes | no | no | **yes** |
| Full SQL (aggregates, unions, windows, DML) | — | no | yes | **yes** | no — deliberate |
| Build-time needs | — | — | codegen | database/DDL + codegen | annotation processor |
| Licence | — | Apache-2.0 | Apache-2.0 | dual (OSS/commercial) | Apache-2.0 |

And the case for choosing them: if your queries genuinely need aggregation, set operations or
window functions as a matter of course, jOOQ is the right tool — use it for those queries. The two
coexist without conflict, precisely because this library deliberately stops where full SQL begins.

## Where it's going

**Nothing goes to Maven Central until it's whole.** Maven Central is permanent, so the first
release will be one you can actually run end to end — generate a metamodel, build a query, execute
it — rather than a milestone of parts.

The 1.x plugin is maintained through the transition and retired in stages. The two generations emit
different shapes — that is the point, since 1.x emits into Spring's own packages — and each shape is
pinned by its own committed corpus of golden files, so neither drifts while both exist.
[`ROADMAP.md`](ROADMAP.md) has the order of work; [`CHANGELOG.md`](CHANGELOG.md) has what actually
exists, in detail.

## Building from source

Nothing here is published, so build it locally:

```bash
git clone https://github.com/VadimBabich/entity-metamodel.git
cd entity-metamodel
mvn -B install
```

That installs the plugin as `1.1.0-SNAPSHOT`, which is the version to reference in your build.
`mvn -B verify` additionally runs the integration tests: the plugin generates against a sample
consumer, compiles the result and diffs it against the golden corpus, and the 2.x execution suite
runs its statements against PostgreSQL in Testcontainers (skipped with a notice if Docker is not
available locally).

## Contributing

[`CONTRIBUTING.md`](CONTRIBUTING.md) covers the build and the conventions; the reactive rules in
[`docs/reactive-code-style.md`](docs/reactive-code-style.md) are contractual for anything touching
the runtime. Security reports go through GitHub's private advisories, per
[`SECURITY.md`](SECURITY.md) — please don't open a public issue for those.

Bug reports are welcome; use-case reports more so. The 2.x design came from measured usage in real
codebases rather than from guesswork, and that only keeps working if people describe what they are
actually doing.
