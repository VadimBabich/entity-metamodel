package io.github.vadimbabich.entitymetamodel.processor.emit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.core.AttributeDescriptor;
import io.github.vadimbabich.entitymetamodel.core.EntityDescriptor;
import io.github.vadimbabich.entitymetamodel.core.EntityModel;
import io.github.vadimbabich.entitymetamodel.core.TypeKind;
import io.github.vadimbabich.entitymetamodel.core.TypeRef;
import io.github.vadimbabich.entitymetamodel.core.WildcardArgument;
import io.github.vadimbabich.entitymetamodel.processor.testing.CollectingDiagnostics;
import io.github.vadimbabich.entitymetamodel.processor.testing.ContractCorpus;
import io.github.vadimbabich.entitymetamodel.processor.testing.ContractModels;
import io.github.vadimbabich.entitymetamodel.processor.testing.RecordingSink;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The generator against the frozen contract corpus, one pinned decision per test. Assertions are on
 * bytes because formatting and member order are part of the contract.
 */
class MetamodelSourceGeneratorTest {

  private final RecordingSink sink = new RecordingSink();
  private final CollectingDiagnostics diagnostics = new CollectingDiagnostics();

  @Test
  void springSemanticsIncludeAnUnannotatedFieldAndExcludeATransientOne() {
    generate(ContractModels.account());

    assertThat(sink.sourceOf("com.example.contract.Account__"))
        .isEqualTo(ContractCorpus.golden("Account__"));
  }

  @Test
  void primitivesBoxInTheTypeArgumentWhileTheRawTokenStaysPrimitive() {
    generate(ContractModels.payment());

    assertThat(sink.sourceOf("com.example.contract.Payment__"))
        .isEqualTo(ContractCorpus.golden("Payment__"));
  }

  @Test
  void declaredGenericsArraysAndWildcardsCarryIntoTheTypeArgument() {
    generate(ContractModels.inventory());

    assertThat(sink.sourceOf("com.example.contract.Inventory__"))
        .isEqualTo(ContractCorpus.golden("Inventory__"));
  }

  @Test
  void inheritedMembersFlattenIntoTheEntityAndInterleaveAlphabetically() {
    generate(ContractModels.legacyDocument());

    assertThat(sink.sourceOf("com.example.contract.LegacyDocument__"))
        .isEqualTo(ContractCorpus.golden("LegacyDocument__"));
  }

  @Test
  void aNestedEntityBecomesANestedMetamodelBeforeThePrivateConstructor() {
    generate(ContractModels.vendor());

    assertThat(sink.sourceOf("com.example.contract.Vendor__"))
        .isEqualTo(ContractCorpus.golden("Vendor__"));
  }

  @Test
  void anEntityNestedInANonEntityBecomesATopLevelMetamodelPrefixedWithItsEncloser() {
    generate(ContractModels.wrapperAttachment());

    assertThat(sink.sourceOf("com.example.contract.Wrapper_Attachment__"))
        .isEqualTo(ContractCorpus.golden("Wrapper_Attachment__"));
  }

  // The corpus carries one upper-bounded wildcard, one type argument and a one-dimensional array.
  // Every other shape is pinned only on TypeRef.canonical(), which no production path calls, so the
  // renderer that does emit them — separator included — would drift with the suite still green.
  @Test
  void typeArgumentShapesAbsentFromTheCorpusStillRenderAsDeclared() {
    generate(EntityDescriptor.builder("com.example.contract", "com.example.contract.Ledger",
            TypeKind.CLASS)
        .tableName("ledgers")
        .attribute(attribute("labels", TypeRef.parameterized("java.util.Map",
            List.of(TypeRef.of("java.lang.String"), TypeRef.of("java.lang.Integer")))))
        .attribute(attribute("ceilings", TypeRef.parameterized("java.util.List",
            List.of(WildcardArgument.lowerBounded(TypeRef.of("java.lang.Integer"))))))
        .attribute(attribute("marker", TypeRef.parameterized("java.lang.Class",
            List.of(WildcardArgument.unbounded()))))
        .attribute(attribute("grid", TypeRef.array(TypeRef.of("java.lang.String"), 2)))
        .build());

    assertThat(sink.sourceOf("com.example.contract.Ledger__"))
        .contains("PropertyRef<Ledger, Map<String, Integer>> LABELS")
        .contains("PropertyRef<Ledger, List<? super Integer>> CEILINGS")
        .contains("PropertyRef<Ledger, Class<?>> MARKER")
        .contains("PropertyRef<Ledger, String[][]> GRID");
  }

  @Test
  void anEntityInTheDefaultPackageGetsNoPackageDeclaration() {
    generate(EntityDescriptor.builder("", "Standalone", TypeKind.CLASS)
        .tableName("standalone")
        .attribute(AttributeDescriptor.of("id", TypeRef.of("java.lang.Long"), true, List.of()))
        .build());

    assertThat(sink.sourceOf("Standalone__"))
        .doesNotContain("package")
        .contains("public final class Standalone__ {");
  }

  private static AttributeDescriptor attribute(String name, TypeRef declaredType) {
    return AttributeDescriptor.of(name, declaredType, false, List.of());
  }

  private void generate(EntityDescriptor... entities) {
    new MetamodelSourceGenerator(InclusionPolicy.SPRING_SEMANTICS, ExistingTypes.NONE)
        .generate(EntityModel.of(List.of(entities)), sink, diagnostics);
  }
}
