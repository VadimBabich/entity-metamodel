package io.github.vadimbabich.entitymetamodel.core;

/**
 * Either a type or a wildcard — the two things a generic type argument can be. A wildcard is not a
 * type, so it cannot be a {@link TypeRef}: {@code List<? extends Number>} has an argument that no
 * field could ever be declared as.
 */
public sealed interface TypeArgument permits TypeRef, WildcardArgument {

  /** Renders the argument as declared Java source text, with names left fully qualified. */
  String canonical();
}
