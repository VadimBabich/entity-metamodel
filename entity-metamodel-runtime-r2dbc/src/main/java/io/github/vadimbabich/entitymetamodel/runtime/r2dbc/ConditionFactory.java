package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.Predicate;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;

public final class ConditionFactory {

  private static final ConditionFactory INSTANCE = new ConditionFactory();

  private ConditionFactory() {}

  public static ConditionFactory getInstance() {
    return INSTANCE;
  }

  public Predicate build(String operator, PropertyRef<?, ?> property, Object... values) {
    return switch (operator) {
      case "equal" -> new SimpleCondition(SimpleCondition.Operator.EQUAL, property, values[0]);
      case "in" -> new SimpleCondition(SimpleCondition.Operator.IN, property, values[0]);
      case "isNull" -> new SimpleCondition(SimpleCondition.Operator.IS_NULL, property, null);
      case "greaterThan" -> new SimpleCondition(SimpleCondition.Operator.GT, property, values[0]);
      case "greaterThanOrEqual" -> new SimpleCondition(SimpleCondition.Operator.GTE, property, values[0]);
      case "lessThan" -> new SimpleCondition(SimpleCondition.Operator.LT, property, values[0]);
      case "lessThanOrEqual" -> new SimpleCondition(SimpleCondition.Operator.LTE, property, values[0]);
      case "like" -> new SimpleCondition(SimpleCondition.Operator.LIKE, property, values[0]);
      default -> throw new IllegalArgumentException("Unknown operator: " + operator);
    };
  }
}
