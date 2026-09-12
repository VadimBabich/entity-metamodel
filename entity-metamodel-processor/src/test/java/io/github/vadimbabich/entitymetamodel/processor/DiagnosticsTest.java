package io.github.vadimbabich.entitymetamodel.processor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.processor.analysis.MappingAnnotations;
import io.github.vadimbabich.entitymetamodel.processor.testing.CompilationOutcome;
import io.github.vadimbabich.entitymetamodel.processor.testing.FixtureCompiler;
import io.github.vadimbabich.entitymetamodel.processor.testing.Fixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.TypeElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the processor says when it cannot generate what a consumer might expect.
 *
 * <p>Two rules held in place: nothing is dropped in silence — 1.x logged and skipped, which is how
 * members went missing unnoticed, so every gap here has an anchored diagnostic — and the compiler's
 * own errors are never repeated.
 */
class DiagnosticsTest {

  @TempDir
  Path workDirectory;

  @Test
  void anEmbeddedMemberIsReportedRatherThanEmittedAsAnOrdinaryRef() {
    CompilationOutcome outcome = compile("EmbeddedHolder.java", "Address.java");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.notes()).anyMatch(note -> note.contains("EM-N3")
        && note.contains("address"));
    assertThat(generatedSource(outcome, "EmbeddedHolder__"))
        .contains("public static final PropertyRef<EmbeddedHolder, String> LABEL")
        .doesNotContain("ADDRESS");
  }

  @Test
  void anEntityWithAnUnresolvableMemberIsSkippedWithoutRepeatingTheCompilersError() {
    CompilationOutcome outcome = compile("BrokenEntity.java", "HealthyEntity.java");

    assertThat(outcome.succeeded()).isFalse();
    // Anchored: a note nobody can navigate to is the logged-and-skipped shape this replaces.
    assertThat(outcome.notes()).anyMatch(note -> note.contains("EM-N2")
        && note.contains("BrokenEntity")
        && note.contains("BrokenEntity.java"));
    assertThat(outcome.errors()).noneMatch(error -> error.contains("EM-"));

    // The broken entity costs its own metamodel and nothing else.
    assertThat(outcome.generatedSources().keySet())
        .containsExactly(Fixtures.generatedPathOf("HealthyEntity__"));
  }

  @Test
  void anInheritedTypeVariableIsResolvedThroughTheEntityThatExtendsIt() {
    CompilationOutcome outcome = compile("Box.java", "GenericBase.java");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.notes()).isEmpty();
    assertThat(generatedSource(outcome, "Box__"))
        .contains("public static final PropertyRef<Box, Long> ID")
        .contains("public static final PropertyRef<Box, String> PAYLOAD");
  }

  @Test
  void aTypeVariableThatNoViewpointResolvesIsStillReported() {
    CompilationOutcome outcome = compile("UnresolvableHolder.java", "GenericBase.java");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.notes()).anyMatch(note -> note.contains("EM-N6")
        && note.contains("payload"));
    assertThat(generatedSource(outcome, "UnresolvableHolder_Leaf__"))
        .contains("ID")
        .doesNotContain("PAYLOAD");
  }

  @Test
  void aHandWrittenMetamodelNameIsAnErrorRatherThanASilentOverwrite() {
    CompilationOutcome outcome = compile("Clashing.java", "Clashing__.java");

    assertThat(outcome.errors()).anyMatch(error -> error.contains("EM-E6")
        && error.contains("Clashing__"));
    assertThat(outcome.generatedSources()).isEmpty();
  }

  @Test
  void theStrictnessOptionDropsPersistentFieldsWithoutAColumnAnnotation() {
    CompilationOutcome outcome = compileWithOptions(
        List.of("LooseEntity.java"), "-A" + ProcessorOptions.REQUIRE_COLUMN_ANNOTATION + "=true");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.notes()).anyMatch(note -> note.contains("EM-N4")
        && note.contains("nickname"));
    assertThat(generatedSource(outcome, "LooseEntity__"))
        .contains("ID")
        .doesNotContain("NICKNAME");
  }

  @Test
  void aBareStrictnessFlagMeansOnAsItDoesEverywhereElseInJavac() {
    CompilationOutcome outcome = compileWithOptions(
        List.of("LooseEntity.java"), "-A" + ProcessorOptions.REQUIRE_COLUMN_ANNOTATION);

    assertThat(outcome.succeeded()).isTrue();
    assertThat(generatedSource(outcome, "LooseEntity__")).doesNotContain("NICKNAME");
  }

  @Test
  void anUnreadableStrictnessValueIsRefusedRatherThanTreatedAsOff() {
    CompilationOutcome outcome = compileWithOptions(
        List.of("LooseEntity.java"), "-A" + ProcessorOptions.REQUIRE_COLUMN_ANNOTATION + "=yes");

    assertThat(outcome.errors()).anyMatch(error -> error.contains("EM-E9")
        && error.contains("requireColumnAnnotation")
        && error.contains("yes"));
  }

  @Test
  void springSemanticsKeepThoseFieldsByDefault() {
    CompilationOutcome outcome = compile("LooseEntity.java");

    assertThat(outcome.notes()).isEmpty();
    assertThat(generatedSource(outcome, "LooseEntity__")).contains("NICKNAME");
  }

  @Test
  void twoPropertyNamesThatTransformToOneMemberNameAreReportedNotEmittedTwice() {
    CompilationOutcome outcome = compile("CollidingNames.java");

    assertThat(outcome.errors()).anyMatch(error -> error.contains("EM-E7")
        && error.contains("source_url")
        && error.contains("sourceUrl"));
  }

  @Test
  void aGenericEntityIsReportedRatherThanEmittedWithRawTypes() {
    CompilationOutcome outcome = compile("GenericEntity.java");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.notes()).anyMatch(note -> note.contains("EM-N7")
        && note.contains("GenericEntity"));
    assertThat(outcome.generatedSources()).isEmpty();
  }

  @Test
  void aMetamodelLeftBehindByAnEarlierBuildIsRegeneratedRatherThanCalledACollision() {
    FixtureCompiler firstBuild = new FixtureCompiler(workDirectory.resolve("first"));
    CompilationOutcome first = firstBuild.compile(
        Fixtures.sources(Fixtures.diagnosticsSource("HealthyEntity.java")), new EntityMetamodelProcessor());

    assertThat(first.errors()).isEmpty();

    // A rebuild without a clean: last build's output directory is on the compile classpath.
    CompilationOutcome second =
        new FixtureCompiler(workDirectory.resolve("second"), firstBuild.classOutput()).compile(
            Fixtures.sources(Fixtures.diagnosticsSource("HealthyEntity.java")), new EntityMetamodelProcessor());

    assertThat(second.errors()).isEmpty();
    assertThat(second.generatedSources())
        .containsKey(Fixtures.generatedPathOf("HealthyEntity__"));
  }

  @Test
  void aReferenceToAnotherAggregateIsReportedRatherThanEmittedAsAColumnRef() {
    CompilationOutcome outcome = compile("ParentAggregate.java", "ChildAggregate.java");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.notes()).anyMatch(note -> note.contains("EM-N3")
        && note.contains("children"));
    assertThat(outcome.notes()).anyMatch(note -> note.contains("EM-N3")
        && note.contains("soleChild"));
    assertThat(generatedSource(outcome, "ParentAggregate__"))
        .contains("LABEL")
        .doesNotContain("CHILDREN")
        .doesNotContain("SOLE_CHILD");
  }

  @Test
  void anEntityNameCarryingTheReservedSeparatorIsRejectedAtCompileTime() {
    CompilationOutcome outcome = compile("Odd__Name.java");

    assertThat(outcome.errors()).anyMatch(error -> error.contains("EM-E8")
        && error.contains("Odd__Name"));
    assertThat(outcome.generatedSources()).isEmpty();
  }

  @Test
  void aSharedSupertypeGapIsReportedOncePerSupertypeNotOncePerSubclass() {
    CompilationOutcome outcome = compile("UnresolvableHolder.java", "GenericBase.java");

    assertThat(outcome.notes()).filteredOn(note -> note.contains("EM-N6")).hasSize(1);
  }

  @Test
  void aPropertyMappingToTheReservedEntityMemberIsRefused() {
    CompilationOutcome outcome = compile("ReservedMemberHolder.java");

    assertThat(outcome.errors()).anyMatch(error -> error.contains("EM-E7")
        && error.contains("entity")
        && error.contains("ENTITY"));
  }

  @Test
  void anUnresolvedInheritedMemberAbandonsTheEntityInsteadOfDroppingTheColumn() {
    CompilationOutcome outcome = compile("InheritsUnresolved.java", "UnresolvedBase.java");

    assertThat(outcome.notes()).anyMatch(note -> note.contains("EM-N2")
        && note.contains("InheritsUnresolved"));
    assertThat(outcome.notes()).noneMatch(note -> note.contains("EM-N6"));
    assertThat(outcome.generatedSources()).isEmpty();
  }

  @Test
  void anEntityBehindAWildcardBoundIsStillARelationship() {
    CompilationOutcome outcome = compile("WildcardParent.java", "ChildAggregate.java");

    assertThat(outcome.notes()).anyMatch(note -> note.contains("EM-N3")
        && note.contains("wildcardKids"));
    assertThat(generatedSource(outcome, "WildcardParent__"))
        .doesNotContain("PLAIN_KIDS")
        .doesNotContain("WILDCARD_KIDS");
  }

  @Test
  void anAnnotationOnTheAccessorCountsAsTheMappingContextCountsIt() {
    CompilationOutcome outcome = compile("AccessorAnnotated.java");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(generatedSource(outcome, "AccessorAnnotated__"))
        .contains("property(\"renamed\"")
        .doesNotContain("property(\"note\"");
  }

  @Test
  void aTypeUseAnnotationOnAPrimitiveDoesNotLeakIntoTheGeneratedType() {
    CompilationOutcome outcome = compile("ValidatedEntity.java", "Positive.java");

    // A Bean Validation constraint is ordinary code, but javac puts it in the mirror's toString and
    // a generated file carrying it does not parse.
    assertThat(outcome.errors()).isEmpty();
    assertThat(outcome.succeeded()).isTrue();
    assertThat(generatedSource(outcome, "ValidatedEntity__"))
        .contains("PropertyRef<ValidatedEntity, Integer> AMOUNT = ENTITY.property(\"amount\","
            + " int.class)")
        .doesNotContain("Positive");
  }

  @Test
  void aValuePopulatedPropertyIsTransientToSpringAndGetsNoMember() {
    CompilationOutcome outcome = compile("SpelPopulated.java");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(generatedSource(outcome, "SpelPopulated__"))
        .contains("ID")
        .doesNotContain("DERIVED");
  }

  @Test
  void aReferenceToAnEntityDeclaredThroughAStereotypeIsStillARelationship() {
    CompilationOutcome outcome =
        compile("ComposedRefHolder.java", "ComposedChild.java", "Aggregate.java");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.notes()).anyMatch(note -> note.contains("EM-N3")
        && note.contains("children"));
    assertThat(generatedSource(outcome, "ComposedRefHolder__")).doesNotContain("CHILDREN");
  }

  @Test
  void anAccessorForAnAcronymFieldIsMatchedByJavaBeansRules() {
    CompilationOutcome outcome = compile("AcronymAccessor.java");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(generatedSource(outcome, "AcronymAccessor__")).doesNotContain("property(\"URL\"");
  }

  @Test
  void anAccessorInASubclassReachesTheFieldItsSupertypeDeclares() {
    CompilationOutcome outcome = compile("AccessorSubclass.java", "AccessorBase.java");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(generatedSource(outcome, "AccessorSubclass__"))
        .doesNotContain("property(\"inherited\"");
  }

  @Test
  void aTransientReachedThroughTwoLevelsOfComposedAnnotationIsStillTransient() {
    CompilationOutcome outcome =
        compile("ComposedTransients.java", "MyTransient.java", "Computed.java");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(generatedSource(outcome, "ComposedTransients__"))
        .contains("KEPT")
        .doesNotContain("ONE_LEVEL")
        .doesNotContain("TWO_LEVEL");
  }

  @Test
  void aFieldNameContainingADollarIsAColumnLikeAnyOther() {
    CompilationOutcome outcome = compile("DollarNamed.java");

    assertThat(outcome.notes()).isEmpty();
    assertThat(generatedSource(outcome, "DollarNamed__")).contains("PRICE$USD");
  }

  @Test
  void aShadowedInheritedMemberIsReportedWithoutClaimingTheEntityDeclaredIt() {
    CompilationOutcome outcome =
        compile("ShadowLeaf.java", "ShadowMiddle.java", "ShadowBase.java");

    assertThat(outcome.notes()).anyMatch(note -> note.contains("EM-N5")
        && note.contains("ShadowBase.label")
        && note.contains("nearer declaration"));
  }

  @Test
  void anUnresolvableSupertypeIsReportedAsOneRatherThanBlamedOnTheFields() {
    CompilationOutcome outcome = compile("Orphan.java");

    // Its only field resolves; the extends clause does not. Sending the reader to the fields is the
    // mislabelling that made the inherited-member case hard to diagnose.
    assertThat(outcome.notes()).anyMatch(note -> note.contains("EM-N2")
        && note.contains("Orphan")
        && note.contains("supertype"));
    assertThat(outcome.errors()).noneMatch(error -> error.contains("EM-"));
  }

  @Test
  void anEntityWaitingForAGeneratedTypeIsRetriedInTheNextRound() {
    CompilationOutcome outcome = new FixtureCompiler(workDirectory).compileWith(
        Fixtures.sources(Fixtures.diagnosticsSource("DeferredEntity.java")),
        List.of(new EntityMetamodelProcessor(), new GeneratesLaterProcessor()));

    assertThat(outcome.errors()).isEmpty();
    assertThat(outcome.generatedSources())
        .containsKey(Fixtures.generatedPathOf("DeferredEntity__"));
  }

  @Test
  void anEntityWhoseSupertypeArrivesLateStillGetsItsInheritedMembers() {
    CompilationOutcome outcome = new FixtureCompiler(workDirectory).compileWith(
        Fixtures.sources(Fixtures.diagnosticsSource("DeferredSubclass.java")),
        List.of(new EntityMetamodelProcessor(), new GeneratesLaterProcessor()));

    assertThat(outcome.errors()).isEmpty();
    assertThat(generatedSource(outcome, "DeferredSubclass__"))
        .contains("ID")
        .contains("PAYLOAD");
  }

  @Test
  void aMemberTypeTheGeneratedClassCannotAccessIsReportedRatherThanBreakingTheBuild() {
    CompilationOutcome outcome = compile("PrivateNestedType.java");

    assertThat(outcome.errors()).isEmpty();
    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.notes()).anyMatch(note -> note.contains("EM-N6")
        && note.contains("status")
        && note.contains("access"));
    assertThat(generatedSource(outcome, "PrivateNestedType__"))
        .contains("ID")
        .doesNotContain("STATUS")
        .doesNotContain("PrivateNestedType.Status");
  }

  @Test
  void anUnannotatedSubclassOfAnEntityIsAnEntityWhereverItIsReferenced() {
    CompilationOutcome outcome = compile(
        "InheritedTableRefHolder.java", "InheritedTableSubclass.java", "InheritedTableBase.java");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.notes()).anyMatch(note -> note.contains("EM-N3")
        && note.contains("sole"));
    assertThat(outcome.notes()).anyMatch(note -> note.contains("EM-N3")
        && note.contains("many"));
    assertThat(generatedSource(outcome, "InheritedTableRefHolder__"))
        .contains("LABEL")
        .doesNotContain("SOLE")
        .doesNotContain("MANY");
    assertThat(outcome.generatedSources())
        .containsKey(Fixtures.generatedPathOf("InheritedTableSubclass__"));
  }

  @Test
  void anInheritedMemberHiddenByAnExcludedNearerDeclarationIsNotEmittedInItsPlace() {
    CompilationOutcome outcome =
        compile("HidingLeaf.java", "HiddenBase.java", "ChildAggregate.java");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.notes()).anyMatch(note -> note.contains("EM-N3")
        && note.contains("items"));
    assertThat(generatedSource(outcome, "HidingLeaf__"))
        .contains("ID")
        .doesNotContain("ITEMS");
  }

  @Test
  void anEntityInTheDefaultPackageReferencesItsNestedTypeWithoutAnImport() {
    CompilationOutcome outcome = new FixtureCompiler(workDirectory).compile(
        Fixtures.sources("diagnostics/DefaultPackaged.java"), new EntityMetamodelProcessor());

    assertThat(outcome.errors()).isEmpty();
    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.generatedText("DefaultPackaged__.java"))
        .doesNotContain("import DefaultPackaged")
        .contains("PropertyRef<DefaultPackaged, DefaultPackaged.Kind> KIND");
  }

  @Test
  void anAnnotationTypeOrInterfaceCarryingTableGetsNoMetamodel() {
    CompilationOutcome outcome =
        compile("Aggregate.java", "TableInterface.java", "ComposedChild.java");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.generatedSources()).isEmpty();
  }

  @Test
  void anEntityNestedInAnAnnotatedInterfaceIsItsOwnRootRatherThanPromotingTheInterface() {
    CompilationOutcome outcome = compile("TableInterfaceOwner.java");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.generatedSources().keySet())
        .containsExactly(Fixtures.generatedPathOf("TableInterfaceOwner_Owned__"));
  }

  @Test
  void anInnerClassOfAParameterizedOuterIsReportedRatherThanEmittedRaw() {
    CompilationOutcome outcome = compileWithOptions(
        List.of("InnerOfGenericHolder.java", "GenericOuter.java"), "-Xlint:rawtypes");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.warnings()).isEmpty();
    assertThat(outcome.notes()).anyMatch(note -> note.contains("EM-N6")
        && note.contains("inner"));
    assertThat(generatedSource(outcome, "InnerOfGenericHolder__"))
        .contains("ID")
        .doesNotContain("INNER");
  }

  @Test
  void anEntityNameEndingWithAnUnderscoreIsRejectedAtCompileTime() {
    CompilationOutcome outcome = compile("Trailing_.java");

    assertThat(outcome.errors()).anyMatch(error -> error.contains("EM-E8")
        && error.contains("Trailing_"));
    assertThat(outcome.generatedSources()).isEmpty();
  }

  /** Stands in for any other processor in the build: contributes a type only in the first round. */
  @SupportedAnnotationTypes(MappingAnnotations.TABLE)
  private static final class GeneratesLaterProcessor extends AbstractProcessor {

    private boolean written;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
      if (written || round.processingOver()) {
        return false;
      }

      written = true;

      try (Writer source = processingEnv.getFiler()
          .createSourceFile("com.example.diagnostics.GeneratedLater").openWriter()) {
        source.write("""
            package com.example.diagnostics;

            import org.springframework.data.relational.core.mapping.Column;

            public class GeneratedLater {
              @Column("payload")
              String payload;
            }
            """);
      } catch (IOException e) {
        throw new UncheckedIOException("cannot write the fixture type", e);
      }

      return false;
    }
  }

  private CompilationOutcome compile(String... fixtureNames) {
    return compileWithOptions(List.of(fixtureNames));
  }

  private CompilationOutcome compileWithOptions(List<String> fixtureNames, String... options) {
    String[] relativePaths = fixtureNames.stream().map(Fixtures::diagnosticsSource)
        .toArray(String[]::new);

    return new FixtureCompiler(workDirectory)
        .compile(Fixtures.sources(relativePaths), new EntityMetamodelProcessor(), options);
  }

  private String generatedSource(CompilationOutcome outcome, String metamodelSimpleName) {
    return outcome.generatedText(Fixtures.generatedPathOf(metamodelSimpleName));
  }
}
