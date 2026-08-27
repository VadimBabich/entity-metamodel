package io.github.vadimbabich.entitymetamodel.runtime;

/**
 * One term of a statement's sort — a property of a table instance, or a raw expression for what the
 * typed vocabulary cannot say. Named for the sort, since "order" alone would read as a business
 * order here.
 */
public sealed interface SortOrder permits PropertySort, ExpressionSort {

  enum Direction {
    ASCENDING,
    DESCENDING,
  }

  Direction direction();
}
