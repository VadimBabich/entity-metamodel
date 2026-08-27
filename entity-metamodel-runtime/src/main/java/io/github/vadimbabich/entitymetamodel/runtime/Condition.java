package io.github.vadimbabich.entitymetamodel.runtime;

import java.util.Objects;

/**
 * Immutable description of a boolean predicate over entity properties — names, an operator and
 * values, nothing else. Rendering belongs to an execution module, which is what keeps substrate
 * types out of this module's API.
 *
 * <p>Composition builds a {@link Junction} tree rather than a flat list, so a renderer knows where
 * one operand ends: an OR folded into a surrounding AND without that boundary re-associates under
 * SQL precedence and silently widens the match.
 */
public sealed interface Condition
    permits Comparison, Inclusion, Junction, Negation, NullCheck, PropertyEquality, SqlExpr {

  default Condition and(Condition other) {
    Objects.requireNonNull(other, "other");

    return new Junction(Junction.Operator.AND, this, other);
  }

  default Condition or(Condition other) {
    Objects.requireNonNull(other, "other");

    return new Junction(Junction.Operator.OR, this, other);
  }

  /**
   * This condition inverted. Repeated negation is kept rather than folded away, since equality is
   * what lets a derived statement recognise a condition it already carries.
   */
  default Condition not() {
    return new Negation(this);
  }
}
