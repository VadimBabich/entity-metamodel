package io.github.vadimbabich.entitymetamodel.processor.emit;

import io.github.vadimbabich.entitymetamodel.core.TypeArgument;
import io.github.vadimbabich.entitymetamodel.core.TypeRef;
import io.github.vadimbabich.entitymetamodel.core.WildcardArgument;
import java.util.Collection;

/**
 * Renders a declared type as source text through an {@link ImportScope}, and collects a unit's
 * referenced types so the scope can be built before anything is written.
 */
final class TypeText {

  private TypeText() {
  }

  static void collectReferences(TypeArgument argument, Collection<String> into) {
    if (argument instanceof TypeRef type) {
      into.add(type.qualifiedName());

      for (TypeArgument typeArgument : type.typeArguments()) {
        collectReferences(typeArgument, into);
      }
    } else if (argument instanceof WildcardArgument wildcard) {
      for (TypeRef bound : wildcard.boundType()) {
        collectReferences(bound, into);
      }
    }
  }

  static String render(TypeArgument argument, ImportScope scope) {
    if (argument instanceof TypeRef type) {
      return renderType(type, scope);
    }

    return renderWildcard((WildcardArgument) argument, scope);
  }

  private static String renderType(TypeRef type, ImportScope scope) {
    StringBuilder rendered = new StringBuilder(scope.render(type.qualifiedName()));

    if (!type.typeArguments().isEmpty()) {
      rendered.append('<');

      for (int i = 0; i < type.typeArguments().size(); i++) {
        if (i > 0) {
          rendered.append(", ");
        }
        rendered.append(render(type.typeArguments().get(i), scope));
      }

      rendered.append('>');
    }

    rendered.append("[]".repeat(type.arrayDimensions()));
    return rendered.toString();
  }

  private static String renderWildcard(WildcardArgument wildcard, ImportScope scope) {
    return switch (wildcard.bound()) {
      case UNBOUNDED -> "?";
      case EXTENDS -> "? extends " + renderType(wildcard.boundType().get(0), scope);
      case SUPER -> "? super " + renderType(wildcard.boundType().get(0), scope);
    };
  }
}
