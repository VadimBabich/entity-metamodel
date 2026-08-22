package io.github.vadimbabich.entitymetamodel.processor.emit;

/**
 * Which declared attributes become generated members. {@link #SPRING_SEMANTICS} is the default and
 * matches what the mapping context serves; {@link #REQUIRE_COLUMN_ANNOTATION} is the strictness
 * opt-in that drops properties carrying no {@code @Column}, as 1.x did.
 */
public enum InclusionPolicy {
  SPRING_SEMANTICS,
  REQUIRE_COLUMN_ANNOTATION
}
