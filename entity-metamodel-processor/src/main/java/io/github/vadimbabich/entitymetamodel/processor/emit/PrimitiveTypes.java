package io.github.vadimbabich.entitymetamodel.processor.emit;

import io.github.vadimbabich.entitymetamodel.core.TypeRef;
import java.util.Map;

/**
 * The boxing rule for ref type arguments: {@code PropertyRef<E,int>} is not legal Java, so a
 * primitive attribute carries its wrapper while the raw-type token stays primitive. An array of
 * primitives is already a reference type and carries as declared.
 */
final class PrimitiveTypes {

  private static final Map<String, String> WRAPPERS = Map.of(
      "boolean", "java.lang.Boolean",
      "byte", "java.lang.Byte",
      "char", "java.lang.Character",
      "short", "java.lang.Short",
      "int", "java.lang.Integer",
      "long", "java.lang.Long",
      "float", "java.lang.Float",
      "double", "java.lang.Double");

  private PrimitiveTypes() {
  }

  static TypeRef boxed(TypeRef declaredType) {
    if (declaredType.arrayDimensions() > 0) {
      return declaredType;
    }

    String wrapper = WRAPPERS.get(declaredType.qualifiedName());

    if (wrapper == null) {
      return declaredType;
    }

    return TypeRef.of(wrapper);
  }
}
