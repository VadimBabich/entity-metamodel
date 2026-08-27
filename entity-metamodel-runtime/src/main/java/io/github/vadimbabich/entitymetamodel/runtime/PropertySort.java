package io.github.vadimbabich.entitymetamodel.runtime;

import java.util.Objects;

public record PropertySort(PropertyRef<?, ?> property, Direction direction) implements SortOrder {

  public PropertySort {
    Objects.requireNonNull(property, "property");
    Objects.requireNonNull(direction, "direction");
  }
}
