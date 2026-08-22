# entity-metamodel

Generated column constants for Spring Data R2DBC, so your queries stop referring to columns by
string.

## What's here

The repository holds two lines at different stages. They release independently, and only one of them
generates code today.

| | What it is | Where to get it | State |
|---|---|---|---|
| **`jpa-metadata-maven-plugin`** | Maven plugin that parses your entity sources and generates metamodels. The working generator. | [build from source](#building-the-plugin) | 1.x, in use |
| **`entity-metamodel-core`**, **`-runtime`**, **`-processor`**, **`-bom`** | The replacement: an annotation processor plus the vocabulary and reference types it generates against. | [in this repository](#the-2x-family) | built, unpublished |

If you want type-safe column references working in your project today, you want the plugin, and the
[five-minute walkthrough](#five-minutes). If you're here to see where this is going, start with
[the 2.x family](#the-2x-family).

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
- **Narrow.** It reads Spring Data Relational annotations and emits constants. It does not wrap your
  repositories, replace `Criteria`, or ask you to adopt a query language.

## Five minutes

Install the plugin into your local repository first — see [Building the plugin](#building-the-plugin).

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
- **Maven only.** There is no Gradle plugin.
- **One package.** Only classes under `packageName` are scanned. Not dependencies, not sibling
  packages.
- **Source-based.** It parses `src/main/java`, so entities that arrive in a jar are invisible to it.
- **Two files land in Spring's own packages** (`Column_`, `StaticR2dbcEntityTemplateAccessor_`).
  That works and is frozen for compatibility, but it is the main thing 2.x removes.
- **Queries are not fully type-safe.** `Criteria` takes `Object` for values, so this buys correct
  *names*, not checked comparisons.

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

Three artifacts are built in this repository and not published — worth reading if you want to see
the shape before it ships, not something to depend on yet.

- **`entity-metamodel-core`** — the model a generator reads and emitters write against:
  `EntityModel`, `EntityDescriptor`, `AttributeDescriptor`, `TypeRef` and friends, plus the
  generation SPI. No dependencies at all.
- **`entity-metamodel-runtime`** — the types generated metamodels compile against, plus the
  owned `@Generated` and `@RawSql` markers. Depends only on `spring-data-relational`.
- **`entity-metamodel-processor`** — the annotation processor. It reads types annotated
  `@Table` — the annotation itself, not a stereotype composed over it, which the compiler cannot
  hand to a processor — through `javax.lang.model`, and emits one metamodel per entity: inherited members flattened in, nested
  entities mirrored, exact declared types carried into the ref type arguments. Compile-time only,
  declares no third-party dependency, and registered in the *isolating* category — so an incremental
  build regenerates a single metamodel instead of all of them, including when the edit was to a
  supertype the entity inherits members from. Its output is held
  byte-for-byte against a committed corpus of golden files on every build.

A reference is held by hand and resolved through Spring's mapping context — the same trick `Column_`
does in 1.x, without the split packages:

```java
EntityRef<User> user = EntityRef.of(User.class);
PropertyRef<User, String> id = user.property("id", String.class);

String column = id.columnName(mappingContext);   // "user_id"
String table = user.tableName(mappingContext);   // "users"
```

`EntityRef.as("u")` gives an aliased instance, which is what joins will be built from. Signatures
can still change; there is no compatibility promise until the first release.

## Where it's going

The replacement is a JSR-269 annotation processor instead of source parsing: build-tool neutral,
incremental-aware, and emitting only into your own packages. The processor generates the frozen
shape today; the R2DBC execution module and the fluent query surface are designed and being built.

**Nothing goes to Maven Central until it generates code.** Publishing a milestone of parts would
spend version numbers on artifacts nobody can use, and Maven Central is permanent — so the first
release will be one you can actually run.

The 1.x plugin is maintained through the transition and retired in stages. The two generations emit
different shapes — that is the point, since 1.x emits into Spring's own packages — and each shape is
pinned by its own committed corpus of golden files, so neither drifts while both exist.
[`ROADMAP.md`](ROADMAP.md) has the order of work; [`CHANGELOG.md`](CHANGELOG.md) has what actually
shipped.

## Building the plugin

Nothing here is published, so build it locally:

```bash
git clone https://github.com/VadimBabich/entity-metamodel.git
cd entity-metamodel
mvn -B install
```

That installs it as `1.1.0-SNAPSHOT`, which is the version to reference in your build. `mvn -B verify`
additionally runs the integration test: it generates against a sample consumer, compiles the result
and diffs it against the golden corpus — a useful check that your JDK produces the same bytes.

## Contributing

[`CONTRIBUTING.md`](CONTRIBUTING.md) covers the build and the conventions. Security reports go
through GitHub's private advisories, per [`SECURITY.md`](SECURITY.md) — please don't open a public
issue for those.

Bug reports are welcome; use-case reports more so. The 2.x design came from measured usage in real
codebases rather than from guesswork, and that only keeps working if people describe what they are
actually doing.
