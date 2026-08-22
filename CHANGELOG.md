# Changelog

## Unreleased

The `entity-metamodel` family — `core`, `runtime` and a BOM — is built in this repository and
**not published**. It holds the model vocabulary and the reference types (`EntityRef`,
`PropertyRef`, `JoinRef`) that generated code will compile against, plus the owned `@Generated` and
`@RawSql` markers.

Nothing generates or executes yet. The first publication will be the version where it does, rather
than a milestone of parts — see [`ROADMAP.md`](ROADMAP.md).

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
