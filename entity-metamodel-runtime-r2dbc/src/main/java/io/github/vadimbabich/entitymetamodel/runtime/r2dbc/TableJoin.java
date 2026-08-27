package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import java.util.Objects;

/**
 * One table instance brought into the statement under one condition. Not called {@code Join}: the
 * substrate has a type of that name. A relationship-derived join and a caller-stated one arrive in
 * the same shape, so the renderer has one join path.
 */
record TableJoin(EntityRef<?> targetInstance, Condition onCondition, Kind kind) {

  enum Kind {
    INNER,
    LEFT_OUTER,
  }

  TableJoin {
    Objects.requireNonNull(targetInstance, "targetInstance");
    Objects.requireNonNull(onCondition, "onCondition");
    Objects.requireNonNull(kind, "kind");
  }
}
