package io.github.vadimbabich.entitymetamodel.runtime;

import java.util.Objects;

/**
 * One property to sort by, and which way. Named for the sort rather than for "order" alone, which
 * in an entity metamodel would read as a business order.
 */
public record SortOrder(PropertyRef<?, ?> property, Direction direction) {

  public enum Direction {
    ASCENDING,
    DESCENDING,
  }

  public SortOrder {
    Objects.requireNonNull(property, "property");
    Objects.requireNonNull(direction, "direction");
  }
}
