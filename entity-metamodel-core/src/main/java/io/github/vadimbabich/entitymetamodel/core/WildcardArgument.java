package io.github.vadimbabich.entitymetamodel.core;

import java.util.List;
import java.util.Objects;

/**
 * A wildcard type argument, carried as declared: {@code ?}, {@code ? extends T} or
 * {@code ? super T}. An unbounded wildcard is kept distinct from {@code ? extends Object} — the two
 * are different declarations, and this model carries what the source says.
 *
 * <p>The bound is a list because an unbounded wildcard has none; that is the same
 * empty-collection idiom the rest of the model uses in place of null.
 */
public record WildcardArgument(Bound bound, List<TypeRef> boundType) implements TypeArgument {

  public enum Bound {
    UNBOUNDED,
    EXTENDS,
    SUPER
  }

  public WildcardArgument {
    Objects.requireNonNull(bound, "bound");
    Objects.requireNonNull(boundType, "boundType");
    boundType = List.copyOf(boundType);

    if (bound == Bound.UNBOUNDED && !boundType.isEmpty()) {
      throw new IllegalArgumentException("An unbounded wildcard must not carry a bound");
    }
    if (bound != Bound.UNBOUNDED && boundType.size() != 1) {
      throw new IllegalArgumentException("A " + bound + " wildcard requires exactly one bound");
    }
  }

  public static WildcardArgument unbounded() {
    return new WildcardArgument(Bound.UNBOUNDED, List.of());
  }

  public static WildcardArgument upperBounded(TypeRef bound) {
    return new WildcardArgument(Bound.EXTENDS, List.of(bound));
  }

  public static WildcardArgument lowerBounded(TypeRef bound) {
    return new WildcardArgument(Bound.SUPER, List.of(bound));
  }

  @Override
  public String canonical() {
    return switch (bound) {
      case UNBOUNDED -> "?";
      case EXTENDS -> "? extends " + boundType.get(0).canonical();
      case SUPER -> "? super " + boundType.get(0).canonical();
    };
  }
}
