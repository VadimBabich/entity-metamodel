package io.github.vadimbabich.entitymetamodel.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The model's behavioral contract: entities carry a total order by qualified name, everything is
 * deeply immutable, and inheritance structure is carried rather than flattened.
 */
class EntityModelContractTest {

  @Test
  void entitiesAreTotallyOrderedByQualifiedNameRegardlessOfInsertionOrder() {
    EntityDescriptor zebra = entity("com.example.Zebra");
    EntityDescriptor apple = entity("com.example.Apple");
    EntityDescriptor mango = entity("com.example.Mango");

    EntityModel model = EntityModel.of(List.of(zebra, apple, mango));

    assertThat(model.entities())
        .extracting(EntityDescriptor::qualifiedName)
        .containsExactly("com.example.Apple", "com.example.Mango", "com.example.Zebra");
  }

  @Test
  void aNullComponentIsRefusedByNameInsteadOfFailingWhereItIsFirstRead() {
    assertThatNullPointerException()
        .isThrownBy(() -> EntityDescriptor.builder("com.example", "com.example.Ghost", null).build())
        .withMessage("kind");
    assertThatNullPointerException()
        .isThrownBy(() -> EntityDescriptor
            .builder("com.example", "com.example.Ghost", TypeKind.CLASS)
            .tableName(null)
            .build())
        .withMessage("tableName");
    assertThatNullPointerException()
        .isThrownBy(() -> AttributeDescriptor.of("id", null, true, List.of()))
        .withMessage("declaredType");
    assertThatNullPointerException()
        .isThrownBy(() -> new WildcardArgument(null, List.of(TypeRef.of("java.lang.String"))))
        .withMessage("bound");
  }

  @Test
  void attributeDeclarationOrderIsPreservedVerbatim() {
    EntityDescriptor payment = EntityDescriptor
        .builder("com.example", "com.example.Payment", TypeKind.RECORD)
        .tableName("payments")
        .attribute(attribute("id", TypeRef.of("java.lang.Long"), true))
        .attribute(attribute("settledOn", TypeRef.of("java.time.LocalDate"), false))
        .attribute(attribute("amount", TypeRef.of("java.math.BigDecimal"), false))
        .build();

    // Within-type order is the frontend's reading order; sorting for emission is the generator's.
    assertThat(payment.attributes())
        .extracting(AttributeDescriptor::name)
        .containsExactly("id", "settledOn", "amount");
  }

  @Test
  void theModelIsDeeplyImmutable() {
    List<AttributeDescriptor> mutableAttributes = new ArrayList<>();
    mutableAttributes.add(attribute("id", TypeRef.of("java.lang.Long"), true));

    EntityDescriptor account = EntityDescriptor
        .builder("com.example", "com.example.Account", TypeKind.CLASS)
        .tableName("accounts")
        .attributes(mutableAttributes)
        .build();

    mutableAttributes.clear();

    assertThat(account.attributes()).hasSize(1);
    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> account.attributes().clear());
  }

  @Test
  void inheritanceStructureIsCarriedNotFlattened() {
    SuperTypeContribution base = SuperTypeContribution.of(
        "com.example.BaseDocument",
        List.of(attribute("createdBy", TypeRef.of("java.lang.String"), false)));

    EntityDescriptor legacyDocument =
        EntityDescriptor.builder("com.example", "com.example.LegacyDocument", TypeKind.CLASS)
            .tableName("legacy_documents")
            .attribute(attribute("id", TypeRef.of("java.lang.Long"), true))
            .superType(base)
            .build();

    assertThat(legacyDocument.attributes()).extracting(AttributeDescriptor::name)
        .containsExactly("id");
    assertThat(legacyDocument.superTypes()).hasSize(1);
    assertThat(legacyDocument.superTypes().get(0).qualifiedName())
        .isEqualTo("com.example.BaseDocument");
    assertThat(legacyDocument.superTypes().get(0).attributes())
        .extracting(AttributeDescriptor::name)
        .containsExactly("createdBy");
  }

  @Test
  void nestedEntitiesMirrorSourceNestingInDeclarationOrder() {
    EntityDescriptor nestedView = entity("com.example.Vendor.VendorPermissionView");

    EntityDescriptor vendor = EntityDescriptor
        .builder("com.example", "com.example.Vendor", TypeKind.CLASS)
        .tableName("vendors")
        .nestedEntity(nestedView)
        .build();

    assertThat(vendor.nestedEntities()).containsExactly(nestedView);
  }

  @Test
  void identityCarriesThePackageSoTheNestedNameChainIsUnambiguous() {
    EntityDescriptor nestedView = EntityDescriptor
        .builder("com.example", "com.example.Vendor.VendorPermissionView", TypeKind.RECORD)
        .tableName("vendor_permission_views")
        .build();

    assertThat(nestedView.packageName()).isEqualTo("com.example");
    assertThat(nestedView.simpleNameChain()).containsExactly("Vendor", "VendorPermissionView");
    assertThat(nestedView.simpleName()).isEqualTo("VendorPermissionView");
  }

  @Test
  void aTopLevelEntityHasASingleNameInItsChain() {
    assertThat(entity("com.example.Account").simpleNameChain()).containsExactly("Account");
  }

  @Test
  void aQualifiedNameOutsideItsOwnPackageIsRejected() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> EntityDescriptor
            .builder("com.example", "com.other.Account", TypeKind.CLASS)
            .build());
  }

  @Test
  void annotationFactsCarryIdentityAndDeclaredValues() {
    AnnotationFact column = AnnotationFact.of(
        "org.springframework.data.relational.core.mapping.Column",
        Map.of("value", "\"account_id\""));

    assertThat(column.qualifiedName())
        .isEqualTo("org.springframework.data.relational.core.mapping.Column");
    assertThat(column.simpleName()).isEqualTo("Column");
    assertThat(column.declaredValues()).containsEntry("value", "\"account_id\"");
  }

  @Test
  void blankIdentityIsRejectedAtConstruction() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> EntityDescriptor.builder("com.example", " ", TypeKind.CLASS).build());
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> attribute(" ", TypeRef.of("java.lang.Long"), false));
  }

  private static EntityDescriptor entity(String qualifiedName) {
    return EntityDescriptor.builder("com.example", qualifiedName, TypeKind.CLASS)
        .tableName("t")
        .build();
  }

  private static AttributeDescriptor attribute(String name, TypeRef type, boolean id) {
    return AttributeDescriptor.of(name, type, id, List.of());
  }
}
