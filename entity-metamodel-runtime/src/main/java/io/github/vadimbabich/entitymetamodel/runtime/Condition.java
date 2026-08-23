package io.github.vadimbabich.entitymetamodel.runtime;

import java.util.Objects;

/**
 * Immutable description of a boolean predicate over entity properties. A condition names properties,
 * an operator and values and nothing else; rendering it to SQL belongs to an execution module, which
 * is what keeps every substrate type out of this module's public API.
 *
 * <p>Composition builds a {@link Junction} tree rather than flattening into a list, so a renderer
 * always knows where one operand ends. An OR folded into a surrounding AND without that boundary
 * re-associates under SQL's precedence rules and silently widens the match.
 */
public sealed interface Condition permits Comparison, Inclusion, Junction, NullCheck, SqlExpr {

  default Condition and(Condition other) {
    Objects.requireNonNull(other, "other");

    return new Junction(Junction.Operator.AND, this, other);
  }

  default Condition or(Condition other) {
    Objects.requireNonNull(other, "other");

    return new Junction(Junction.Operator.OR, this, other);
  }
}
