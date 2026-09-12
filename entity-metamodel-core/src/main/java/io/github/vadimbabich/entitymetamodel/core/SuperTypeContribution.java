package io.github.vadimbabich.entitymetamodel.core;

import java.util.List;
import java.util.Objects;

/**
 * The attributes one supertype contributes, attributed to their declaring type. The model carries
 * hierarchy structure; flattening and its ordering are a per-generator choice.
 */
public record SuperTypeContribution(String qualifiedName, List<AttributeDescriptor> attributes) {

  public SuperTypeContribution {
    TypeRef.requireText(qualifiedName, "qualifiedName");
    Objects.requireNonNull(attributes, "attributes");
    attributes = List.copyOf(attributes);
  }

  public static SuperTypeContribution of(
      String qualifiedName, List<AttributeDescriptor> attributes) {
    return new SuperTypeContribution(qualifiedName, attributes);
  }
}
