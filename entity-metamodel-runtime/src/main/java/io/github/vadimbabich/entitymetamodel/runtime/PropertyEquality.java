package io.github.vadimbabich.entitymetamodel.runtime;

import java.util.Objects;

/**
 * Two properties compared to each other — the shape of a join condition, where both sides are
 * columns. Equality is the only relation offered because it is the only one a join demands.
 */
public record PropertyEquality(PropertyRef<?, ?> left, PropertyRef<?, ?> right)
    implements Condition {

  public PropertyEquality {
    Objects.requireNonNull(left, "left");
    Objects.requireNonNull(right, "right");
  }
}
