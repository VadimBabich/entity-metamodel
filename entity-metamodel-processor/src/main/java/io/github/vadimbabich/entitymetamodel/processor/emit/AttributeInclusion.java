package io.github.vadimbabich.entitymetamodel.processor.emit;

import io.github.vadimbabich.entitymetamodel.core.AnnotationFact;
import io.github.vadimbabich.entitymetamodel.core.AttributeDescriptor;
import java.util.List;

/**
 * Decides which declared attributes become generated members, following Spring's
 * persistent-property rules rather than the presence of {@code @Column}.
 *
 * <p>A verdict rather than a boolean because two exclusions are gaps this version has not closed —
 * embedded values and mapped collections need a member shape that is not frozen — and those deserve
 * a report rather than a silent drop.
 */
final class AttributeInclusion {

  // Spring's definition: @Transient, or populated by the container rather than by the row.
  private static final List<String> TRANSIENT = List.of(
      "org.springframework.data.annotation.Transient",
      "org.springframework.beans.factory.annotation.Value",
      "org.springframework.beans.factory.annotation.Autowired");
  private static final String COLUMN = "org.springframework.data.relational.core.mapping.Column";
  private static final String EMBEDDED =
      "org.springframework.data.relational.core.mapping.Embedded";
  private static final String MAPPED_COLLECTION =
      "org.springframework.data.relational.core.mapping.MappedCollection";

  private final InclusionPolicy policy;

  AttributeInclusion(InclusionPolicy policy) {
    this.policy = policy;
  }

  enum Verdict {
    INCLUDED,
    TRANSIENT_PROPERTY,
    EMBEDDED_VALUE,
    MAPPED_COLLECTION_MEMBER,
    WITHOUT_COLUMN_ANNOTATION
  }

  Verdict verdictOn(AttributeDescriptor attribute) {
    for (String transientAnnotation : TRANSIENT) {
      if (carries(attribute, transientAnnotation)) {
        return Verdict.TRANSIENT_PROPERTY;
      }
    }
    if (carries(attribute, EMBEDDED)) {
      return Verdict.EMBEDDED_VALUE;
    }
    if (carries(attribute, MAPPED_COLLECTION)) {
      return Verdict.MAPPED_COLLECTION_MEMBER;
    }
    if (policy == InclusionPolicy.REQUIRE_COLUMN_ANNOTATION && !carries(attribute, COLUMN)) {
      return Verdict.WITHOUT_COLUMN_ANNOTATION;
    }

    return Verdict.INCLUDED;
  }

  private boolean carries(AttributeDescriptor attribute, String annotationQualifiedName) {
    return AnnotationFact.presentIn(attribute.annotations(), annotationQualifiedName);
  }
}
