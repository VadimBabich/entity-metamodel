package io.github.vadimbabich.entitymetamodel.processor.analysis;

import io.github.vadimbabich.entitymetamodel.core.AnnotationFact;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeKind;

/**
 * Carries annotations as source-level literals. Only explicitly declared members are recorded: a
 * default a consumer never wrote is not a fact about their source.
 *
 * <p>Mapping meta-annotations are carried alongside the annotations that declare them, because
 * Spring's shortcut forms mean what they mean that way: {@code @Embedded.Nullable} is embedded only
 * by the {@code @Embedded} on the shortcut, and without this the commonest way to write an embedded
 * member would look like an ordinary column.
 */
final class AnnotationFacts {

  private static final String MAPPING_NAMESPACE = "org.springframework.data.";
  private static final String JDK_ANNOTATIONS = "java.lang.annotation.";

  private AnnotationFacts() {
  }

  static List<AnnotationFact> factsOf(List<? extends AnnotationMirror> mirrors) {
    Map<String, AnnotationFact> byQualifiedName = new LinkedHashMap<>();
    Set<String> walked = new HashSet<>();

    // Direct declarations first: the author's own @Embedded(...) must not lose its values to the
    // same annotation reached through a sibling. Mirror order then stops mattering.
    for (AnnotationMirror mirror : mirrors) {
      byQualifiedName.put(mirror.getAnnotationType().toString(), factOf(mirror));
    }
    for (AnnotationMirror mirror : mirrors) {
      collectMappingMetaAnnotations(mirror, byQualifiedName, walked);
    }

    return List.copyOf(byQualifiedName.values());
  }

  private static void collectMappingMetaAnnotations(
      AnnotationMirror mirror, Map<String, AnnotationFact> collected, Set<String> walked) {

    for (AnnotationMirror meta : mirror.getAnnotationType().asElement().getAnnotationMirrors()) {
      String qualifiedName = meta.getAnnotationType().toString();

      // Nothing to read from the JDK's own meta-annotations or an unresolved one (a missing
      // annotation jar); `walked` is what terminates the walk.
      if (qualifiedName.startsWith(JDK_ANNOTATIONS)
          || meta.getAnnotationType().getKind() == TypeKind.ERROR
          || !walked.add(qualifiedName)) {
        continue;
      }

      if (qualifiedName.startsWith(MAPPING_NAMESPACE)) {
        collected.putIfAbsent(qualifiedName, factOf(meta));
      }

      collectMappingMetaAnnotations(meta, collected, walked);
    }
  }

  private static AnnotationFact factOf(AnnotationMirror mirror) {
    return AnnotationFact.of(mirror.getAnnotationType().toString(), declaredValuesOf(mirror));
  }

  private static Map<String, String> declaredValuesOf(AnnotationMirror mirror) {
    Map<String, String> declaredValues = new LinkedHashMap<>();

    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> declared
        : mirror.getElementValues().entrySet()) {

      declaredValues.put(
          declared.getKey().getSimpleName().toString(), declared.getValue().toString());
    }

    return declaredValues;
  }
}
