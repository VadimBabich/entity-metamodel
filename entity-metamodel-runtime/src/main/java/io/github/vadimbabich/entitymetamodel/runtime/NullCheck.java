package io.github.vadimbabich.entitymetamodel.runtime;

import java.util.Objects;

/**
 * One property tested for SQL {@code NULL}.
 */
public record NullCheck(PropertyRef<?, ?> property) implements Condition {

  public NullCheck {
    Objects.requireNonNull(property, "property");
  }
}
