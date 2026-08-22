package io.github.vadimbabich.entitymetamodel.processor.testing;

import io.github.vadimbabich.entitymetamodel.core.AnnotationFact;
import io.github.vadimbabich.entitymetamodel.core.AttributeDescriptor;
import io.github.vadimbabich.entitymetamodel.core.EntityDescriptor;
import io.github.vadimbabich.entitymetamodel.core.SuperTypeContribution;
import io.github.vadimbabich.entitymetamodel.core.TypeArgument;
import io.github.vadimbabich.entitymetamodel.core.TypeKind;
import io.github.vadimbabich.entitymetamodel.core.TypeRef;
import io.github.vadimbabich.entitymetamodel.core.WildcardArgument;
import java.util.List;
import java.util.Map;

/**
 * The contract corpus expressed as hand-built models — the same entities the frontend reads from
 * source, built without a compiler.
 *
 * <p>A model in, golden bytes out — which makes the generator provable on its own, and doubles as
 * the generation SPI's walkthrough since nothing here knows a processor exists.
 */
public final class ContractModels {

  private static final String ID = "org.springframework.data.annotation.Id";
  private static final String TRANSIENT = "org.springframework.data.annotation.Transient";
  private static final String VERSION = "org.springframework.data.annotation.Version";
  private static final String COLUMN = "org.springframework.data.relational.core.mapping.Column";

  private ContractModels() {
  }

  /** Inclusion witness: an un-annotated persistent field in, a {@code @Transient} field out. */
  public static EntityDescriptor account() {
    return entity("Account", TypeKind.CLASS, "accounts")
        .attribute(attribute("id", type("java.lang.Long"), fact(ID), column("account_id")))
        .attribute(attribute("ownerEmail", type("java.lang.String"), column("owner_email")))
        .attribute(attribute("nickname", type("java.lang.String")))
        .attribute(attribute("draftNote", type("java.lang.String"),
            fact(TRANSIENT), column("draft_note")))
        .attribute(attribute("sourceURLPath", type("java.lang.String"), column("source_url_path")))
        .build();
  }

  /** Typing witnesses: primitives box in the type argument while the raw token stays primitive. */
  public static EntityDescriptor payment() {
    return entity("Payment", TypeKind.RECORD, "payments")
        .attribute(attribute("id", type("long"), fact(ID), column("payment_id")))
        .attribute(attribute("retryCount", type("int"), column("retry_count")))
        .attribute(attribute("captured", type("boolean"), column("captured")))
        .attribute(attribute("capturedAt", type("java.time.Instant"), column("captured_at")))
        .attribute(attribute("settledOn", type("java.time.LocalDate"), column("settled_on")))
        .attribute(attribute("status", type(ContractCorpus.PACKAGE + ".PaymentStatus"),
            column("status")))
        .build();
  }

  /** Generic-fidelity witnesses: parameterized, array and bounded-wildcard declared types. */
  public static EntityDescriptor inventory() {
    return entity("Inventory", TypeKind.RECORD, "inventories")
        .attribute(attribute("id", type("java.lang.Long"), fact(ID), column("inventory_id")))
        .attribute(attribute("tags", listOf(type("java.lang.String")), column("tags")))
        .attribute(attribute("scores", TypeRef.array(type("int"), 1), column("scores")))
        .attribute(attribute("limits",
            listOf(WildcardArgument.upperBounded(type("java.lang.Number"))), column("limits")))
        .build();
  }

  /** Flattening witness: what the non-{@code @Table} superclass contributes lands here. */
  public static EntityDescriptor legacyDocument() {
    return entity("LegacyDocument", TypeKind.CLASS, "legacy_documents")
        .attribute(attribute("id", type("java.lang.Long"), fact(ID), column("document_id")))
        .attribute(attribute("title", type("java.lang.String"), column("title")))
        .superType(SuperTypeContribution.of(ContractCorpus.PACKAGE + ".BaseDocument", List.of(
            attribute("createdBy", type("java.lang.String"), column("created_by")),
            attribute("revision", type("java.lang.Long"), fact(VERSION), column("revision")))))
        .build();
  }

  /** Nesting witness: an entity nested in an entity is a nested metamodel, named per level. */
  public static EntityDescriptor vendor() {
    EntityDescriptor permissionView =
        entity("Vendor.VendorPermissionView", TypeKind.RECORD, "vendor_permission_views")
            .attribute(attribute("id", type("java.lang.Long"), fact(ID), column("view_id")))
            .attribute(attribute("permissionKey", type("java.lang.String"),
                column("permission_key")))
            .attribute(attribute("vendorId", type("java.lang.Long"), column("vendor_id")))
            .build();

    return entity("Vendor", TypeKind.CLASS, "vendors")
        .attribute(attribute("id", type("java.lang.Long"), fact(ID), column("vendor_id")))
        .attribute(attribute("displayName", type("java.lang.String"), column("display_name")))
        .nestedEntity(permissionView)
        .build();
  }

  /** Encloser-edge witness: the enclosing class is not an entity, so this one stands alone. */
  public static EntityDescriptor wrapperAttachment() {
    return entity("Wrapper.Attachment", TypeKind.RECORD, "attachments")
        .attribute(attribute("id", type("java.lang.Long"), fact(ID), column("attachment_id")))
        .attribute(attribute("fileName", type("java.lang.String"), column("file_name")))
        .build();
  }

  private static EntityDescriptor.Builder entity(String simpleName, TypeKind kind, String table) {
    return EntityDescriptor
        .builder(ContractCorpus.PACKAGE, ContractCorpus.PACKAGE + "." + simpleName, kind)
        .tableName(table);
  }

  private static AttributeDescriptor attribute(
      String name, TypeRef declaredType, AnnotationFact... annotations) {

    return AttributeDescriptor.of(
        name, declaredType, carriesId(annotations), List.of(annotations));
  }

  private static boolean carriesId(AnnotationFact... annotations) {
    for (AnnotationFact annotation : annotations) {
      if (annotation.qualifiedName().equals(ID)) {
        return true;
      }
    }

    return false;
  }

  private static TypeRef type(String qualifiedName) {
    return TypeRef.of(qualifiedName);
  }

  private static TypeRef listOf(TypeArgument elementType) {
    return TypeRef.parameterized("java.util.List", List.of(elementType));
  }

  private static AnnotationFact column(String columnName) {
    return AnnotationFact.of(COLUMN, Map.of("value", '"' + columnName + '"'));
  }

  private static AnnotationFact fact(String qualifiedName) {
    return AnnotationFact.of(qualifiedName, Map.of());
  }
}
