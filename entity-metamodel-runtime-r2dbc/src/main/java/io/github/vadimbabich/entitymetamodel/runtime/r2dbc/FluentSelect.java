package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class FluentSelect<E> {

  private final EntityRef<E> entity;

  private FluentSelect(EntityRef<E> entity) {
    this.entity = entity;
  }

  public static <E> FluentSelect<E> from(EntityRef<E> entity) {
    return new FluentSelect<>(entity);
  }

  public FluentSelect<E> where(Condition condition) {
    return this;
  }

  public Flux<E> all() {
    throw new UnsupportedOperationException("Terminal: all() not yet implemented");
  }

  public Mono<E> one() {
    throw new UnsupportedOperationException("Terminal: one() not yet implemented");
  }

  public Mono<E> first() {
    throw new UnsupportedOperationException("Terminal: first() not yet implemented");
  }

  public Mono<java.util.List<E>> list() {
    throw new UnsupportedOperationException("Terminal: list() not yet implemented");
  }

  public Mono<Long> count() {
    throw new UnsupportedOperationException("Terminal: count() not yet implemented");
  }

  public Mono<Boolean> exists() {
    throw new UnsupportedOperationException("Terminal: exists() not yet implemented");
  }
}
