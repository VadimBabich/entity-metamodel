package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.JoinRef;
import java.util.Objects;

/**
 * One relationship traversed to one target instance. Deliberately not called {@code Join}: the
 * substrate has a type of that name, and a renderer holding both would read ambiguously.
 *
 * <p>{@code T} is carried so the relationship's target and the instance it is anchored to stay
 * provably the same type; erasing it to a pair of wildcards makes them unrelatable again.
 */
record TableJoin<T>(JoinRef<?, T> relationship, EntityRef<T> targetInstance, Kind kind) {

  enum Kind {
    INNER,
    LEFT_OUTER,
  }

  TableJoin {
    Objects.requireNonNull(relationship, "relationship");
    Objects.requireNonNull(targetInstance, "targetInstance");
    Objects.requireNonNull(kind, "kind");
  }
}
