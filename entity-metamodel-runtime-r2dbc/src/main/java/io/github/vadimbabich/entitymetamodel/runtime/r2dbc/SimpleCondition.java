package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.Predicate;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import java.util.Objects;

final class SimpleCondition implements Condition {

  enum Operator {
    EQUAL,
    IN,
    IS_NULL,
    GT,
    GTE,
    LT,
    LTE,
    LIKE,
    AND,
    OR,
  }

  private final Operator operator;
  private final PropertyRef<?, ?> property;
  private final Object value;
  private final Condition left;
  private final Condition right;

  SimpleCondition(Operator operator, PropertyRef<?, ?> property, Object value) {
    this.operator = operator;
    this.property = Objects.requireNonNull(property, "property");
    this.value = value;
    this.left = null;
    this.right = null;
  }

  SimpleCondition(Operator operator, Condition left, Condition right) {
    this.operator = operator;
    this.property = null;
    this.value = null;
    this.left = Objects.requireNonNull(left, "left");
    this.right = Objects.requireNonNull(right, "right");
  }

  @Override
  public Condition and(Predicate other) {
    if (!(other instanceof Condition)) {
      throw new IllegalArgumentException("Operand must be a Condition");
    }
    return new SimpleCondition(Operator.AND, this, (Condition) other);
  }

  @Override
  public Condition or(Predicate other) {
    if (!(other instanceof Condition)) {
      throw new IllegalArgumentException("Operand must be a Condition");
    }
    return new SimpleCondition(Operator.OR, this, (Condition) other);
  }

  Operator operator() {
    return operator;
  }

  PropertyRef<?, ?> property() {
    return property;
  }

  Object value() {
    return value;
  }

  Condition left() {
    return left;
  }

  Condition right() {
    return right;
  }

  @Override
  public String toString() {
    return "SimpleCondition[" + operator + "]";
  }
}
