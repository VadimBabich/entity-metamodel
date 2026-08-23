package io.github.vadimbabich.entitymetamodel.runtime;

import java.util.Objects;

/**
 * One property compared against one bound value.
 */
public record Comparison(PropertyRef<?, ?> property, Operator operator, Object value)
    implements Condition {

  public enum Operator {
    EQUAL,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    LIKE,
  }

  public Comparison {
    Objects.requireNonNull(property, "property");
    Objects.requireNonNull(operator, "operator");

    // No row satisfies '= NULL', so accepting null here would render a query that executes
    // happily and returns nothing. isNull() is the only way to ask about absence.
    Objects.requireNonNull(value, "value must not be null - use isNull() to test for absence");
  }
}
