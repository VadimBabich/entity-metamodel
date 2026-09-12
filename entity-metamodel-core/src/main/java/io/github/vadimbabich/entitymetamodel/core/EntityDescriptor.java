package io.github.vadimbabich.entitymetamodel.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Everything a frontend knows about one entity type. Attributes keep declaration order and
 * supertypes extends order; built once by a frontend, read by generators.
 *
 * <p>Identity is the package plus the qualified name: a qualified name alone cannot say where the
 * package ends and the nesting begins, and a generator has to emit both the package declaration and
 * the source-visible {@code Vendor.VendorPermissionView} name.
 */
public record EntityDescriptor(
    String packageName,
    String qualifiedName,
    TypeKind kind,
    String tableName,
    List<AttributeDescriptor> attributes,
    List<SuperTypeContribution> superTypes,
    List<EntityDescriptor> nestedEntities) {

  public EntityDescriptor {
    TypeRef.requireText(qualifiedName, "qualifiedName");
    requireQualifiedNameInPackage(packageName, qualifiedName);
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(tableName, "tableName");
    Objects.requireNonNull(attributes, "attributes");
    Objects.requireNonNull(superTypes, "superTypes");
    Objects.requireNonNull(nestedEntities, "nestedEntities");
    attributes = List.copyOf(attributes);
    superTypes = List.copyOf(superTypes);
    nestedEntities = List.copyOf(nestedEntities);
  }

  public static Builder builder(String packageName, String qualifiedName, TypeKind kind) {
    return new Builder(packageName, qualifiedName, kind);
  }

  /** Simple names outermost first: {@code [Vendor, VendorPermissionView]}. */
  public List<String> simpleNameChain() {
    if (packageName.isEmpty()) {
      return List.of(qualifiedName.split("\\."));
    }

    String nestedNames = qualifiedName.substring(packageName.length() + 1);

    return List.of(nestedNames.split("\\."));
  }

  public String simpleName() {
    List<String> chain = simpleNameChain();

    return chain.get(chain.size() - 1);
  }

  private static void requireQualifiedNameInPackage(String packageName, String qualifiedName) {
    if (packageName == null) {
      throw new IllegalArgumentException("packageName must not be null");
    }
    if (packageName.isEmpty()) {
      return;
    }
    if (!qualifiedName.startsWith(packageName + ".")) {
      throw new IllegalArgumentException(
          "Qualified name '" + qualifiedName + "' does not lie in package '" + packageName + "'");
    }
  }

  public static final class Builder {

    private final String packageName;
    private final String qualifiedName;
    private final TypeKind kind;
    private String tableName = "";
    private final List<AttributeDescriptor> attributes = new ArrayList<>();
    private final List<SuperTypeContribution> superTypes = new ArrayList<>();
    private final List<EntityDescriptor> nestedEntities = new ArrayList<>();

    private Builder(String packageName, String qualifiedName, TypeKind kind) {
      this.packageName = packageName;
      this.qualifiedName = qualifiedName;
      this.kind = kind;
    }

    public Builder tableName(String declaredTableName) {
      this.tableName = declaredTableName;
      return this;
    }

    public Builder attribute(AttributeDescriptor attribute) {
      this.attributes.add(attribute);
      return this;
    }

    public Builder attributes(List<AttributeDescriptor> declarationOrdered) {
      this.attributes.addAll(declarationOrdered);
      return this;
    }

    public Builder superType(SuperTypeContribution contribution) {
      this.superTypes.add(contribution);
      return this;
    }

    public Builder nestedEntity(EntityDescriptor nested) {
      this.nestedEntities.add(nested);
      return this;
    }

    public EntityDescriptor build() {
      return new EntityDescriptor(
          packageName, qualifiedName, kind, tableName, attributes, superTypes, nestedEntities);
    }
  }
}
