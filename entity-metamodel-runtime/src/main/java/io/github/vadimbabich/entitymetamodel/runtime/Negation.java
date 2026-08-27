package io.github.vadimbabich.entitymetamodel.runtime;

import java.util.Objects;

/**
 * One condition inverted. Wraps rather than rewrites: turning {@code =} into {@code <>} needs a
 * rule per operator and per nesting level, and the two differ once {@code NULL} is involved.
 */
public record Negation(Condition condition) implements Condition {

  public Negation {
    Objects.requireNonNull(condition, "condition");
  }
}
