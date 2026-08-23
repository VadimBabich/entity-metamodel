package io.github.vadimbabich.entitymetamodel.runtime;

import java.util.Objects;

/**
 * Two conditions combined. The pair stays a tree node and is never flattened into a list — the
 * boundary is what lets a renderer group the operand correctly ({@link Condition}).
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
