package io.github.vadimbabich.entitymetamodel.processor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.processor.testing.CompilationOutcome;
import io.github.vadimbabich.entitymetamodel.processor.testing.FixtureCompiler;
import io.github.vadimbabich.entitymetamodel.processor.testing.Fixtures;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How referenced types are written into the generated source.
 *
 * <p>Both cases are a name that looks resolvable and is not, or resolves to the wrong type.
 * Compilation succeeding is part of the assertion: a consumer cannot fix a generated file.
 */
class EmissionRenderingTest {

  @TempDir
  Path workDirectory;

  @Test
  void aTypeInASubPackageOfTheEntitysPackageIsImportedNotWrittenRelatively() {
    CompilationOutcome outcome = compile("SubPackageHolder.java", "money/Money.java");

    assertThat(outcome.errors()).isEmpty();
    assertThat(outcome.succeeded()).isTrue();
    assertThat(generatedSource(outcome, "SubPackageHolder__"))
        .contains("import com.example.diagnostics.money.Money;")
        .contains("PropertyRef<SubPackageHolder, Money> AMOUNT");
  }

  @Test
  void anImportNeverShadowsTheEntityTheMetamodelIsFor() {
    CompilationOutcome outcome = compile("ShadowedEntity.java", "other/ShadowedEntity.java");

    assertThat(outcome.errors()).isEmpty();
    assertThat(generatedSource(outcome, "ShadowedEntity__"))
        .contains("EntityRef<ShadowedEntity> ENTITY = EntityRef.of(ShadowedEntity.class)")
        .contains("PropertyRef<ShadowedEntity, com.example.diagnostics.other.ShadowedEntity>");
  }

  @Test
  void aSamePackageTypeIsQualifiedWhenTheUnitDeclaresThatNameItself() {
    CompilationOutcome outcome = compile("CollidingHolder.java", "Inner__.java");

    assertThat(outcome.errors()).isEmpty();
    // Bare Inner__ inside CollidingHolder__ binds to the nested metamodel, not the property's own
    // type — and compiles, with the wrong type argument.
    assertThat(generatedSource(outcome, "CollidingHolder__"))
        .contains("PropertyRef<CollidingHolder, com.example.diagnostics.Inner__> MARKER");
  }

  @Test
  void aJavaLangTypeIsQualifiedWhenTheEntitysOwnPackageClaimsItsSimpleName() {
    CompilationOutcome outcome = compile("ShadowHolder.java", "Integer.java");

    assertThat(outcome.errors()).isEmpty();
    assertThat(generatedSource(outcome, "ShadowHolder__"))
        .contains("PropertyRef<ShadowHolder, java.lang.Integer> COUNT")
        .contains("java.lang.Integer.class");
  }

  private CompilationOutcome compile(String... fixtureNames) {
    String[] relativePaths = Stream.of(fixtureNames).map(Fixtures::diagnosticsSource)
        .toArray(String[]::new);

    return new FixtureCompiler(workDirectory)
        .compile(Fixtures.sources(relativePaths), new EntityMetamodelProcessor());
  }

  private String generatedSource(CompilationOutcome outcome, String metamodelSimpleName) {
    return outcome.generatedText(Fixtures.generatedPathOf(metamodelSimpleName));
  }
}
