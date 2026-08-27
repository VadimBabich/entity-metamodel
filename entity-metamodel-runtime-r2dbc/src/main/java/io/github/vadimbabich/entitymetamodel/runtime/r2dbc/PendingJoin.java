package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import java.util.Objects;

/**
 * A join waiting for its condition, so that the type system rather than a runtime check is what
 * stops a joined table from carrying no {@code ON} clause — which SQL reads as a cross join.
 */
public final class PendingJoin<E> {

  private final FluentSelect<E> select;
  private final EntityRef<?> targetInstance;
  private final TableJoin.Kind kind;

  PendingJoin(FluentSelect<E> select, EntityRef<?> targetInstance, TableJoin.Kind kind) {
    this.select = select;
    this.targetInstance = targetInstance;
    this.kind = kind;
  }

  /**
   * States the join condition. Both sides are properties of instances the statement names, so the
   * builder still owns every alias.
   *
   * <p>A condition naming an instance joined <em>later</em> is deliberately not checked: it
   * renders, and the database rejects it naming the alias, so the one mistake this door allows
   * cannot be silent. Guarding it would need a second walk over the sealed condition vocabulary to
   * improve an error that already points at the problem.
   */
  public FluentSelect<E> on(Condition condition) {
    Objects.requireNonNull(condition, "condition");

    return select.withJoin(targetInstance, condition, kind);
  }
}
