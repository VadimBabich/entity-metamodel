package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.JoinRef;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyEquality;
import io.github.vadimbabich.entitymetamodel.runtime.SortOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Immutable description of a select: every step returns a new instance, and describing one performs
 * no I/O. Joining a {@link JoinRef} brings its <em>target</em> instance in, starting from the
 * source instance the ref names unless a third argument says otherwise.
 *
 * <p><strong>A join to a to-many side multiplies rows.</strong> {@link #distinct()} collapses the
 * result — and its total — back to distinct projected rows; a filter that only asks whether a
 * counterpart exists is cheaper as an {@code EXISTS} fragment, which multiplies nothing.
 */
public final class FluentSelect<E> {

  private final EntityRef<E> entity;
  private final Condition whereCondition;
  private final List<TableJoin> joins;
  private final List<SortOrder> sortOrders;
  private final List<EntityRef<?>> alsoSelected;
  private final Long limit;
  private final Long offset;
  private final boolean distinct;

  private FluentSelect(
      EntityRef<E> entity, Condition whereCondition, List<TableJoin> joins,
      List<SortOrder> sortOrders, List<EntityRef<?>> alsoSelected, Long limit, Long offset,
      boolean distinct) {

    this.entity = entity;
    this.whereCondition = whereCondition;
    this.joins = joins;
    this.sortOrders = sortOrders;
    this.alsoSelected = alsoSelected;
    this.limit = limit;
    this.offset = offset;
    this.distinct = distinct;
  }

  public static <E> FluentSelect<E> from(EntityRef<E> entity) {
    Objects.requireNonNull(entity, "entity");

    return new FluentSelect<>(entity, null, List.of(), List.of(), List.of(), null, null, false);
  }

  // One field per copier, so no step method restates the constructor's argument list — it carries
  // an adjacent Long pair, and a transposed limit/offset compiles silently.
  private FluentSelect<E> withWhereCondition(Condition whereCondition) {
    return new FluentSelect<>(
        entity, whereCondition, joins, sortOrders, alsoSelected, limit, offset, distinct);
  }

  private FluentSelect<E> withJoins(List<TableJoin> joins) {
    return new FluentSelect<>(
        entity, whereCondition, joins, sortOrders, alsoSelected, limit, offset, distinct);
  }

  private FluentSelect<E> withSortOrders(List<SortOrder> sortOrders) {
    return new FluentSelect<>(
        entity, whereCondition, joins, sortOrders, alsoSelected, limit, offset, distinct);
  }

  private FluentSelect<E> withAlsoSelected(List<EntityRef<?>> alsoSelected) {
    return new FluentSelect<>(
        entity, whereCondition, joins, sortOrders, alsoSelected, limit, offset, distinct);
  }

  private FluentSelect<E> withLimit(Long limit) {
    return new FluentSelect<>(
        entity, whereCondition, joins, sortOrders, alsoSelected, limit, offset, distinct);
  }

  private FluentSelect<E> withOffset(Long offset) {
    return new FluentSelect<>(
        entity, whereCondition, joins, sortOrders, alsoSelected, limit, offset, distinct);
  }

  private FluentSelect<E> withDistinctRows() {
    return new FluentSelect<>(
        entity, whereCondition, joins, sortOrders, alsoSelected, limit, offset, true);
  }

  /**
   * Also projects this instance's columns, so a mapper can read its entity out of each row. Joining
   * alone does not, because most joins exist to filter. Asking twice projects once.
   */
  public FluentSelect<E> alsoSelect(EntityRef<?> instance) {
    Objects.requireNonNull(instance, "instance");

    if (instance.equals(entity) || alsoSelected.contains(instance)) {
      return this;
    }

    List<EntityRef<?>> extended = new ArrayList<>(alsoSelected);
    extended.add(instance);

    return withAlsoSelected(List.copyOf(extended));
  }

  /**
   * A second filter is AND-ed onto the first, never replacing it.
   */
  public FluentSelect<E> where(Condition condition) {
    Objects.requireNonNull(condition, "condition");

    Condition narrowed = condition;
    if (whereCondition != null) {
      narrowed = whereCondition.and(condition);
    }

    return withWhereCondition(narrowed);
  }

  /**
   * Appends to the existing sort rather than replacing it.
   */
  public FluentSelect<E> orderBy(SortOrder... orders) {
    Objects.requireNonNull(orders, "orders");

    List<SortOrder> extended = new ArrayList<>(sortOrders);
    for (SortOrder order : orders) {
      extended.add(Objects.requireNonNull(order, "order"));
    }

    return withSortOrders(List.copyOf(extended));
  }

  public FluentSelect<E> limit(long rowCount) {
    if (rowCount < 0) {
      throw new IllegalArgumentException("A limit cannot be negative, but was " + rowCount);
    }

    return withLimit(rowCount);
  }

  public FluentSelect<E> offset(long skippedRows) {
    if (skippedRows < 0) {
      throw new IllegalArgumentException("An offset cannot be negative, but was " + skippedRows);
    }

    return withOffset(skippedRows);
  }

  public <T> FluentSelect<E> join(JoinRef<?, T> relationship) {
    Objects.requireNonNull(relationship, "relationship");

    return joinRelationship(relationship, relationship.target().entity(), TableJoin.Kind.INNER);
  }

  /**
   * The only way to traverse one relationship twice.
   */
  public <T> FluentSelect<E> join(JoinRef<?, T> relationship, EntityRef<T> targetInstance) {
    Objects.requireNonNull(relationship, "relationship");
    Objects.requireNonNull(targetInstance, "targetInstance");

    return joinRelationship(relationship, targetInstance, TableJoin.Kind.INNER);
  }

  public <T> FluentSelect<E> leftOuterJoin(JoinRef<?, T> relationship) {
    Objects.requireNonNull(relationship, "relationship");

    return joinRelationship(
        relationship, relationship.target().entity(), TableJoin.Kind.LEFT_OUTER);
  }

  public <T> FluentSelect<E> leftOuterJoin(
      JoinRef<?, T> relationship, EntityRef<T> targetInstance) {
    Objects.requireNonNull(relationship, "relationship");
    Objects.requireNonNull(targetInstance, "targetInstance");

    return joinRelationship(relationship, targetInstance, TableJoin.Kind.LEFT_OUTER);
  }

  /**
   * Joins the target on a named instance, starting from a named instance of the source — the only
   * way past the first hop of a self-referencing relationship.
   */
  public <S, T> FluentSelect<E> join(
      JoinRef<S, T> relationship, EntityRef<S> sourceInstance, EntityRef<T> targetInstance) {

    Objects.requireNonNull(relationship, "relationship");
    Objects.requireNonNull(sourceInstance, "sourceInstance");
    Objects.requireNonNull(targetInstance, "targetInstance");

    return joinRelationship(relationship, sourceInstance, targetInstance, TableJoin.Kind.INNER);
  }

  public <S, T> FluentSelect<E> leftOuterJoin(
      JoinRef<S, T> relationship, EntityRef<S> sourceInstance, EntityRef<T> targetInstance) {

    Objects.requireNonNull(relationship, "relationship");
    Objects.requireNonNull(sourceInstance, "sourceInstance");
    Objects.requireNonNull(targetInstance, "targetInstance");

    return joinRelationship(
        relationship, sourceInstance, targetInstance, TableJoin.Kind.LEFT_OUTER);
  }

  /**
   * Joins an instance on a condition the caller states — the door for what no declared relationship
   * describes, such as an expression in the {@code ON} clause.
   */
  public PendingJoin<E> join(EntityRef<?> targetInstance) {
    return pendingJoin(targetInstance, TableJoin.Kind.INNER);
  }

  public PendingJoin<E> leftOuterJoin(EntityRef<?> targetInstance) {
    return pendingJoin(targetInstance, TableJoin.Kind.LEFT_OUTER);
  }

  /**
   * Collapses the result to distinct projected rows — the remedy when a join to a to-many side
   * multiplies the entity a listing is about. The total and the probe follow, the total at the
   * price of running the selection as a derived table.
   *
   * <p>Sorting a distinct description by a column it does not project is a database error, since
   * {@code DISTINCT} decides which rows survive before the sort runs.
   */
  public FluentSelect<E> distinct() {
    if (distinct) {
      return this;
    }

    return withDistinctRows();
  }

  EntityRef<E> entity() {
    return entity;
  }

  Optional<Condition> whereCondition() {
    return Optional.ofNullable(whereCondition);
  }

  List<TableJoin> joins() {
    return joins;
  }

  List<SortOrder> sortOrders() {
    return sortOrders;
  }

  List<EntityRef<?>> alsoSelected() {
    return alsoSelected;
  }

  boolean isDistinct() {
    return distinct;
  }

  // Paging is kept: it depends on N, not on the order.
  FluentSelect<E> withoutSort() {
    return withSortOrders(List.of());
  }

  // What a mirror count renders: a total is drawn from the filtered set, not from one page —
  // distinct is kept, because it decides what the set's rows are.
  FluentSelect<E> withoutSortAndPaging() {
    return withSortOrders(List.of()).withLimit(null).withOffset(null);
  }

  // Narrows the limit and never widens it: limit(0) selects nothing, and LIMIT 1 would report a row
  // it excludes.
  FluentSelect<E> withAtMostOneRow() {
    if (limit != null && limit < 1) {
      return this;
    }

    return limit(1);
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

  // Re-anchoring is how one relationship reaches N instances; the shared value type was already
  // checked when the JoinRef was built.
  private <S, T> FluentSelect<E> joinRelationship(
      JoinRef<S, T> relationship, EntityRef<S> sourceInstance, EntityRef<T> targetInstance,
      TableJoin.Kind kind) {

    Condition sameKey =
        new PropertyEquality(
            relationship.source().of(sourceInstance),
            relationship.target().of(targetInstance));

    return withJoin(targetInstance, sameKey, kind);
  }

  // Starting from the instance the JoinRef names, which is unambiguous only while the statement
  // carries one instance of that type.
  private <S, T> FluentSelect<E> joinRelationship(
      JoinRef<S, T> relationship, EntityRef<T> targetInstance, TableJoin.Kind kind) {

    EntityRef<S> declaredSource = relationship.source().entity();
    rejectUndeterminedSource(declaredSource, targetInstance);

    return joinRelationship(relationship, declaredSource, targetInstance, kind);
  }

  // Presence applies to every relationship — the render would reject an absent source anyway, but
  // only as an unknown table. Ambiguity applies only where source and target share an entity type:
  // across types the declared anchor is the contract, and refusing would reject valid statements.
  private void rejectUndeterminedSource(EntityRef<?> declaredSource, EntityRef<?> targetInstance) {
    List<EntityRef<?>> present = instances();

    if (!present.contains(declaredSource)) {
      throw new IllegalArgumentException(
          "This relationship starts from " + declaredSource.alias() + ", which this statement does"
              + " not carry; name the instance it should start from with"
              + " join(relationship, sourceInstance, targetInstance)");
    }

    if (!declaredSource.entityType().equals(targetInstance.entityType())) {
      return;
    }

    for (EntityRef<?> other : present) {
      if (other.entityType().equals(declaredSource.entityType())
          && !other.equals(declaredSource)
          && !other.equals(targetInstance)) {

        throw new IllegalArgumentException(
            "This statement carries more than one instance of "
                + declaredSource.entityType().getSimpleName() + " (" + declaredSource.alias()
                + " and " + other.alias() + "), so which one this relationship starts from is"
                + " ambiguous; name it with join(relationship, sourceInstance, targetInstance)");
      }
    }
  }

  // One answer, because the guards and the renderer ask the same question — another way of bringing
  // an instance in would otherwise have to be taught to each of them.
  List<EntityRef<?>> instances() {
    List<EntityRef<?>> present = new ArrayList<>();
    present.add(entity);
    for (TableJoin join : joins) {
      present.add(join.targetInstance());
    }

    return present;
  }

  private PendingJoin<E> pendingJoin(EntityRef<?> targetInstance, TableJoin.Kind kind) {
    Objects.requireNonNull(targetInstance, "targetInstance");

    // Refused before the condition is asked for: the caller is about to write one over an instance
    // that cannot be added.
    rejectInstanceAlreadyPresent(targetInstance);

    return new PendingJoin<>(this, targetInstance, kind);
  }

  FluentSelect<E> withJoin(
      EntityRef<?> targetInstance, Condition onCondition, TableJoin.Kind kind) {
    rejectInstanceAlreadyPresent(targetInstance);

    List<TableJoin> extended = new ArrayList<>(joins);
    extended.add(new TableJoin(targetInstance, onCondition, kind));

    return withJoins(List.copyOf(extended));
  }

  // At the call site rather than at render time: two joins onto one instance both constrain the
  // same table, and that quietly returns an intersection of the two relationships.
  private void rejectInstanceAlreadyPresent(EntityRef<?> targetInstance) {
    if (instances().contains(targetInstance)) {
      throw alreadyPresent(targetInstance);
    }
  }

  private static IllegalArgumentException alreadyPresent(EntityRef<?> instance) {
    return new IllegalArgumentException(
        instance + " is already part of this statement; one table instance is joined once, so"
            + " traversing a relationship again needs a distinct instance from EntityRef.as(...)");
  }
}
