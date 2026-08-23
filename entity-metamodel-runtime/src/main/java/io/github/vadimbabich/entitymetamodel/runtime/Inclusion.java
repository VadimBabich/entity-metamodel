package io.github.vadimbabich.entitymetamodel.runtime;

import java.util.List;
import java.util.Objects;

/**
 * One property tested against a set of bound values — SQL {@code IN}.
 */
public record Inclusion(PropertyRef<?, ?> property, List<?> values) implements Condition {

  public Inclusion {
    Objects.requireNonNull(property, "property");
    Objects.requireNonNull(values, "values");

    if (values.isEmpty()) {
      throw new IllegalArgumentException(
          "An empty IN list is invalid SQL and can match no row; decide at the call site what an"
              + " empty selection means rather than shipping a query that never matches");
    }

    // Copies defensively, and rejects null elements on the way: 'IN (NULL)' never matches, which
    // is the '= NULL' trap one level down.
    values = List.copyOf(values);
  }
}
