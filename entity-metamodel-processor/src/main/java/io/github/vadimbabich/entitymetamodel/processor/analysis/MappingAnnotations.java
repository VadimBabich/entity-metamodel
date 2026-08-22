package io.github.vadimbabich.entitymetamodel.processor.analysis;

import io.github.vadimbabich.entitymetamodel.core.AnnotationFact;
import java.util.Map;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;

/**
 * The two mapping annotations the frontend has to understand; everything else is carried as a fact
 * and interpreted downstream. Matching is on the qualified name — 1.x matched simple names and
 * collected {@code jakarta.persistence.@Table} types as Spring entities.
 */
public final class MappingAnnotations {

  public static final String TABLE = "org.springframework.data.relational.core.mapping.Table";

  static final String ID = "org.springframework.data.annotation.Id";

  private MappingAnnotations() {
  }

  /**
   * True when the type refers to another aggregate rather than to a column, directly or as an array
   * component or type argument. Only the annotated case is decided here: Spring's rule is "not a
   * simple type", which depends on runtime converters, so the runtime asks the mapping context.
   */
  static boolean referencesAnEntity(TypeMirror type) {
    if (type instanceof ArrayType array) {
      return referencesAnEntity(array.getComponentType());
    }
    if (type instanceof WildcardType wildcard) {
      // A bound is where the entity hides in List<? extends Child>; an absent one is null, which
      // the pattern matches below reject on their own.
      return referencesAnEntity(wildcard.getExtendsBound())
          || referencesAnEntity(wildcard.getSuperBound());
    }
    if (!(type instanceof DeclaredType declared)) {
      return false;
    }
    if (isEntity(declared.asElement())) {
      return true;
    }

    for (TypeMirror typeArgument : declared.getTypeArguments()) {
      if (referencesAnEntity(typeArgument)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Whether the type is a mapped entity, through a stereotype as well as directly: a house
   * annotation meta-annotated with {@code @Table} is one to the mapping context, so it is one here.
   *
   * <p>Root discovery stays literal — {@code getElementsAnnotatedWith} does not resolve
   * meta-annotations, so a stereotyped entity gets no metamodel of its own.
   */
  static boolean isEntity(Element element) {
    return AnnotationFact.presentIn(
        AnnotationFacts.factsOf(element.getAnnotationMirrors()), TABLE);
  }

  /** The table name as declared, or empty when the annotation leaves it to the naming strategy. */
  static String declaredTableNameOf(Element entity) {
    for (AnnotationMirror mirror : entity.getAnnotationMirrors()) {
      if (!mirror.getAnnotationType().toString().equals(TABLE)) {
        continue;
      }

      for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> declared
          : mirror.getElementValues().entrySet()) {

        String member = declared.getKey().getSimpleName().toString();

        if (member.equals("value") || member.equals("name")) {
          return String.valueOf(declared.getValue().getValue());
        }
      }
    }

    return "";
  }
}
