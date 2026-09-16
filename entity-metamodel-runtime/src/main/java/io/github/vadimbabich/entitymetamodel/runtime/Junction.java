package io.github.vadimbabich.entitymetamodel.runtime;

import java.util.Objects;

/**
 * Two conditions combined. The pair stays a tree node in the model; a renderer may regroup a run of
 * same-operator pairs, but the boundary between differing operators is what lets it group each
 * operand correctly ({@link Condition}).
 */
public record Junction(Operator operator, Condition left, Condition right) implements Condition {

  public enum Operator {
    AND,
    OR,
  }

  public Junction {
    Objects.requireNonNull(operator, "operator");
    Objects.requireNonNull(left, "left");
    Objects.requireNonNull(right, "right");
  }
}
