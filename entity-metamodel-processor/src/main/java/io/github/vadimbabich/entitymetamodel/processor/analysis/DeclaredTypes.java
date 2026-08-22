package io.github.vadimbabich.entitymetamodel.processor.analysis;

import io.github.vadimbabich.entitymetamodel.core.TypeArgument;
import io.github.vadimbabich.entitymetamodel.core.TypeRef;
import io.github.vadimbabich.entitymetamodel.core.WildcardArgument;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;

/**
 * Converts a resolved {@code TypeMirror} into the model's declared type, verbatim: type arguments,
 * array dimensions and wildcards all carry.
 *
 * <p>Readability is asked before converting because the two unreadable cases need different
 * answers: an unresolvable type is an ERROR mirror the compiler already reports, while a type
 * variable is valid source this contract cannot express as a ref type argument.
 */
final class DeclaredTypes {

  enum Readability {
    READABLE,
    UNRESOLVED,
    NOT_EXPRESSIBLE
  }

  private DeclaredTypes() {
  }

  static Readability readabilityOf(TypeMirror mirror) {
    if (mirror.getKind() == TypeKind.ERROR) {
      return Readability.UNRESOLVED;
    }
    if (mirror.getKind() == TypeKind.TYPEVAR) {
      return Readability.NOT_EXPRESSIBLE;
    }
    if (mirror.getKind() == TypeKind.ARRAY) {
      return readabilityOf(((ArrayType) mirror).getComponentType());
    }
    if (mirror.getKind() == TypeKind.WILDCARD) {
      return readabilityOfWildcard((WildcardType) mirror);
    }
    if (mirror.getKind() == TypeKind.DECLARED) {
      return readabilityOfArguments((DeclaredType) mirror);
    }

    return Readability.READABLE;
  }

  static TypeRef typeRefOf(TypeMirror mirror) {
    if (mirror.getKind() == TypeKind.ARRAY) {
      ArrayType arrayType = (ArrayType) mirror;

      return TypeRef.array(typeRefOf(arrayType.getComponentType()), 1);
    }
    if (mirror.getKind() == TypeKind.DECLARED) {
      DeclaredType declaredType = (DeclaredType) mirror;
      TypeElement element = (TypeElement) declaredType.asElement();

      return TypeRef.parameterized(
          element.getQualifiedName().toString(), argumentsOf(declaredType));
    }

    // The kind, not the mirror's text: a TYPE_USE annotation (every Bean Validation constraint is
    // one) lands in toString(), and "@Positive int" is neither a type name nor importable.
    if (mirror.getKind().isPrimitive()) {
      return TypeRef.of(mirror.getKind().toString().toLowerCase(Locale.ROOT));
    }

    return TypeRef.of(mirror.toString());
  }

  private static List<TypeArgument> argumentsOf(DeclaredType declaredType) {
    List<TypeArgument> arguments = new ArrayList<>();

    for (TypeMirror argument : declaredType.getTypeArguments()) {
      arguments.add(argumentOf(argument));
    }

    return arguments;
  }

  private static TypeArgument argumentOf(TypeMirror mirror) {
    if (mirror.getKind() != TypeKind.WILDCARD) {
      return typeRefOf(mirror);
    }

    WildcardType wildcard = (WildcardType) mirror;

    if (wildcard.getExtendsBound() != null) {
      return WildcardArgument.upperBounded(typeRefOf(wildcard.getExtendsBound()));
    }
    if (wildcard.getSuperBound() != null) {
      return WildcardArgument.lowerBounded(typeRefOf(wildcard.getSuperBound()));
    }

    return WildcardArgument.unbounded();
  }

  private static Readability readabilityOfWildcard(WildcardType wildcard) {
    if (wildcard.getExtendsBound() != null) {
      return readabilityOf(wildcard.getExtendsBound());
    }
    if (wildcard.getSuperBound() != null) {
      return readabilityOf(wildcard.getSuperBound());
    }

    return Readability.READABLE;
  }

  private static Readability readabilityOfArguments(DeclaredType declaredType) {
    for (TypeMirror argument : declaredType.getTypeArguments()) {
      Readability readability = readabilityOf(argument);

      if (readability != Readability.READABLE) {
        return readability;
      }
    }

    return Readability.READABLE;
  }
}
