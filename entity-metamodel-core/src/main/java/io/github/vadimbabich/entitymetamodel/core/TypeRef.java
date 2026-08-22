package io.github.vadimbabich.entitymetamodel.core;

import java.util.List;

/**
 * An exact declared Java type, carried verbatim — the fidelity that makes a generated
 * {@code PropertyRef<E,T>} type argument usable rather than decorative.
 */
public record TypeRef(String qualifiedName, List<TypeArgument> typeArguments, int arrayDimensions)
    implements TypeArgument {

  public TypeRef {
    requireText(qualifiedName, "qualifiedName");
    typeArguments = List.copyOf(typeArguments);
    if (arrayDimensions < 0) {
      throw new IllegalArgumentException("arrayDimensions must not be negative");
    }
  }

  public static TypeRef of(String qualifiedName) {
    return new TypeRef(qualifiedName, List.of(), 0);
  }

  public static TypeRef parameterized(
      String qualifiedName, List<? extends TypeArgument> typeArguments) {
    return new TypeRef(qualifiedName, List.copyOf(typeArguments), 0);
  }

  public static TypeRef array(TypeRef componentType, int dimensions) {
    int totalDimensions = componentType.arrayDimensions() + dimensions;

    return new TypeRef(
        componentType.qualifiedName(), componentType.typeArguments(), totalDimensions);
  }

  @Override
  public String canonical() {
    StringBuilder rendered = new StringBuilder(qualifiedName);

    if (!typeArguments.isEmpty()) {
      rendered.append('<');
      for (int i = 0; i < typeArguments.size(); i++) {
        if (i > 0) {
          rendered.append(", ");
        }
        rendered.append(typeArguments.get(i).canonical());
      }
      rendered.append('>');
    }

    rendered.append("[]".repeat(arrayDimensions));
    return rendered.toString();
  }

  static void requireText(String candidate, String what) {
    if (candidate == null || candidate.isBlank()) {
      throw new IllegalArgumentException(what + " must not be blank");
    }
  }
}
