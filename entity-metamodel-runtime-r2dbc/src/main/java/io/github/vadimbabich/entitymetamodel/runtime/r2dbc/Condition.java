package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.Predicate;

public interface Condition extends Predicate {

  @Override
  Condition and(Predicate other);

  @Override
  Condition or(Predicate other);
}
