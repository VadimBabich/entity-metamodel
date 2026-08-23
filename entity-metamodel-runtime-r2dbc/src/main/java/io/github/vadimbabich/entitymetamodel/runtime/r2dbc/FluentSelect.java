package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.JoinRef;
import io.github.vadimbabich.entitymetamodel.runtime.SortOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Immutable description of a select: every step returns a new instance, so a half-built query is
 * safe to hand to two callers that finish it differently, and describing one performs no I/O.
 *
 * <p>Joining a {@link JoinRef} brings the relationship's <em>target</em> instance into the
 * statement; the source side must already be there, because a relationship is traversed from
 * something the statement already names.
 */
public final class FluentSelect<E> {

  private final EntityRef<E> entity;
  private final Condition whereCondition;
  private final List<TableJoin<?>> joins;
  private final List<SortOrder> sortOrders;
  private final List<EntityRef<?>> alsoSelected;
  private final Long limit;
  private final Long offset;

  private FluentSelect(
      EntityRef<E> entity, Condition whereCondition, List<TableJoin<?>> joins,
      List<SortOrder> sortOrders, List<EntityRef<?>> alsoSelected, Long limit, Long offset) {

    this.entity = entity;
    this.whereCondition = whereCondition;
    this.joins = joins;
    this.sortOrders = sortOrders;
    this.alsoSelected = alsoSelected;
    this.limit = limit;
    this.offset = offset;
  }

  public static <E> FluentSelect<E> from(EntityRef<E> entity) {
    Objects.requireNonNull(entity, "entity");

    return new FluentSelect<>(entity, null, List.of(), List.of(), List.of(), null, null);
  }

  /**
   * Also projects this instance's columns, so a mapper can read its entity out of each row.
   * Separate
   * from joining because most joins exist to filter, and projecting every joined table would fetch
   * columns nobody reads. Asking twice projects once.
   */
  public FluentSelect<E> alsoSelect(EntityRef<?> instance) {
    Objects.requireNonNull(instance, "instance");

    if (instance.equals(entity) || alsoSelected.contains(instance)) {
      return this;
    }

    List<EntityRef<?>> extended = new ArrayList<>(alsoSelected);
    extended.add(instance);

    return new FluentSelect<>(
        entity, whereCondition, joins, sortOrders, List.copyOf(extended), limit, offset);
  }

  /**
   * Narrows the query. A second filter is AND-ed onto the first rather than replacing it, so the
   * conditional-accumulation pattern cannot silently drop an earlier scope.
   */
  public FluentSelect<E> where(Condition condition) {
    Objects.requireNonNull(condition, "condition");

    Condition narrowed = condition;
    if (whereCondition != null) {
      narrowed = whereCondition.and(condition);
    }

    return new FluentSelect<>(entity, narrowed, joins, sortOrders, alsoSelected, limit, offset);
  }

  /** Appends to the existing sort, so repeated calls read the same way as one call with both. */
  public FluentSelect<E> orderBy(SortOrder... orders) {
    Objects.requireNonNull(orders, "orders");

    List<SortOrder> extended = new ArrayList<>(sortOrders);
    for (SortOrder order : orders) {
      extended.add(Objects.requireNonNull(order, "order"));
    }

    return new FluentSelect<>(
        entity, whereCondition, joins, List.copyOf(extended), alsoSelected, limit, offset);
  }

  public FluentSelect<E> limit(long rowCount) {
    if (rowCount < 0) {
      throw new IllegalArgumentException("A limit cannot be negative, but was " + rowCount);
    }

    return new FluentSelect<>(
        entity, whereCondition, joins, sortOrders, alsoSelected, rowCount, offset);
  }

  public FluentSelect<E> offset(long skippedRows) {
    if (skippedRows < 0) {
      throw new IllegalArgumentException("An offset cannot be negative, but was " + skippedRows);
    }

    return new FluentSelect<>(
        entity, whereCondition, joins, sortOrders, alsoSelected, limit, skippedRows);
  }

  /** Joins the relationship's target on its default instance. */
  public <T> FluentSelect<E> join(JoinRef<?, T> relationship) {
    Objects.requireNonNull(relationship, "relationship");

    return addJoin(relationship, relationship.target().entity(), TableJoin.Kind.INNER);
  }

  /** Joins the target on a named instance — the only way to traverse one relationship twice. */
  public <T> FluentSelect<E> join(JoinRef<?, T> relationship, EntityRef<T> targetInstance) {
    Objects.requireNonNull(relationship, "relationship");
    Objects.requireNonNull(targetInstance, "targetInstance");

    return addJoin(relationship, targetInstance, TableJoin.Kind.INNER);
  }

  public <T> FluentSelect<E> leftOuterJoin(JoinRef<?, T> relationship) {
    Objects.requireNonNull(relationship, "relationship");

    return addJoin(relationship, relationship.target().entity(), TableJoin.Kind.LEFT_OUTER);
  }

  public <T> FluentSelect<E> leftOuterJoin(
      JoinRef<?, T> relationship, EntityRef<T> targetInstance) {
    Objects.requireNonNull(relationship, "relationship");
    Objects.requireNonNull(targetInstance, "targetInstance");

    return addJoin(relationship, targetInstance, TableJoin.Kind.LEFT_OUTER);
  }

  EntityRef<E> entity() {
    return entity;
  }

  Optional<Condition> whereCondition() {
    return Optional.ofNullable(whereCondition);
  }

  List<TableJoin<?>> joins() {
    return joins;
  }

  List<SortOrder> sortOrders() {
    return sortOrders;
  }

  List<EntityRef<?>> alsoSelected() {
    return alsoSelected;
  }

  /**
   * The same query without its sort. Paging is kept: how many rows follow the first N does not
   * depend on their order, but it very much depends on N.
   */
  FluentSelect<E> withoutSort() {
    return new FluentSelect<>(
        entity, whereCondition, joins, List.of(), alsoSelected, limit, offset);
  }

  /** What a mirror count renders: a total is drawn from the filtered set, not from one page. */
  FluentSelect<E> withoutSortAndPaging() {
    return new FluentSelect<>(entity, whereCondition, joins, List.of(), alsoSelected, null, null);
  }

  OptionalLong limit() {
    if (limit == null) {
      return OptionalLong.empty();
    }

    return OptionalLong.of(limit);
  }

  OptionalLong offset() {
    if (offset == null) {
      return OptionalLong.empty();
    }

    return OptionalLong.of(offset);
  }

  private <T> FluentSelect<E> addJoin(
      JoinRef<?, T> relationship, EntityRef<T> targetInstance, TableJoin.Kind kind) {

    rejectInstanceAlreadyPresent(targetInstance);

    List<TableJoin<?>> extended = new ArrayList<>(joins);
    extended.add(new TableJoin<>(relationship, targetInstance, kind));

    return new FluentSelect<>(
        entity, whereCondition, List.copyOf(extended), sortOrders, alsoSelected, limit, offset);
  }

  /**
   * Rejected at the call site rather than at render time: two joins onto one instance both
   * constrain the same table, and that quietly returns an intersection of the two relationships.
   */
  private void rejectInstanceAlreadyPresent(EntityRef<?> targetInstance) {
    if (entity.equals(targetInstance)) {
      throw alreadyPresent(targetInstance);
    }

    for (TableJoin<?> existingJoin : joins) {
      if (existingJoin.targetInstance().equals(targetInstance)) {
        throw alreadyPresent(targetInstance);
      }
    }
  }

  private static IllegalArgumentException alreadyPresent(EntityRef<?> instance) {
    return new IllegalArgumentException(
        instance + " is already part of this statement; one table instance is joined once, so"
            + " traversing a relationship again needs a distinct instance from EntityRef.as(...)");
  }
}
