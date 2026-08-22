package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class FluentSelect<E> {

  private final QueryState<E> state;

  private FluentSelect(QueryState<E> state) {
    this.state = Objects.requireNonNull(state, "state");
  }

  public static <E> FluentSelect<E> from(EntityRef<E> entity) {
    Objects.requireNonNull(entity, "entity");
    return new FluentSelect<>(new QueryState<>(entity));
  }

  public FluentSelect<E> where(Condition condition) {
    Objects.requireNonNull(condition, "condition");
    QueryState<E> newState = state.withWhere(condition);
    return new FluentSelect<>(newState);
  }

  public Flux<E> all() {
    return Flux.error(
        new UnsupportedOperationException(
            "Query execution requires QueryExecutor and R2DBC integration"));
  }

  public Mono<E> one() {
    return Mono.error(
        new UnsupportedOperationException(
            "Query execution requires QueryExecutor and R2DBC integration"));
  }

  public Mono<E> first() {
    return Mono.error(
        new UnsupportedOperationException(
            "Query execution requires QueryExecutor and R2DBC integration"));
  }

  public Mono<List<E>> list() {
    return Mono.error(
        new UnsupportedOperationException(
            "Query execution requires QueryExecutor and R2DBC integration"));
  }

  public Mono<Long> count() {
    return Mono.error(
        new UnsupportedOperationException(
            "Query execution requires QueryExecutor and R2DBC integration"));
  }

  public Mono<Boolean> exists() {
    return Mono.error(
        new UnsupportedOperationException(
            "Query execution requires QueryExecutor and R2DBC integration"));
  }
}
