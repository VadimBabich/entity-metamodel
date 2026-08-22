package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.JoinRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

final class QueryState<E> {

  private final EntityRef<E> entity;
  private final Condition whereCondition;
  private final List<JoinInfo> joins;

  QueryState(EntityRef<E> entity) {
    this(entity, null, Collections.emptyList());
  }

  private QueryState(EntityRef<E> entity, Condition whereCondition, List<JoinInfo> joins) {
    this.entity = Objects.requireNonNull(entity, "entity");
    this.whereCondition = whereCondition;
    this.joins = Objects.requireNonNull(joins, "joins");
  }

  EntityRef<E> entity() {
    return entity;
  }

  Condition whereCondition() {
    return whereCondition;
  }

  List<JoinInfo> joins() {
    return joins;
  }

  QueryState<E> withWhere(Condition condition) {
    if (Objects.equals(this.whereCondition, condition)) {
      return this;
    }
    return new QueryState<>(entity, condition, joins);
  }

  QueryState<E> withJoin(JoinInfo joinInfo) {
    List<JoinInfo> newJoins = new ArrayList<>(joins);
    newJoins.add(joinInfo);
    return new QueryState<>(entity, whereCondition, Collections.unmodifiableList(newJoins));
  }

  static final class JoinInfo {
    private final JoinRef<?, ?> joinRef;
    private final Condition onCondition;
    private final JoinType type;

    enum JoinType {
      INNER,
      LEFT_OUTER,
    }

    JoinInfo(JoinRef<?, ?> joinRef, Condition onCondition, JoinType type) {
      this.joinRef = Objects.requireNonNull(joinRef, "joinRef");
      this.onCondition = onCondition;
      this.type = Objects.requireNonNull(type, "type");
    }

    JoinRef<?, ?> joinRef() {
      return joinRef;
    }

    Condition onCondition() {
      return onCondition;
    }

    JoinType type() {
      return type;
    }
  }
}
