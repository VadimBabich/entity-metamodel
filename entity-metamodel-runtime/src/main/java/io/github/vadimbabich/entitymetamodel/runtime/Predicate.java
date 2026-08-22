package io.github.vadimbabich.entitymetamodel.runtime;

public interface Predicate {

  Predicate and(Predicate other);

  Predicate or(Predicate other);
}
