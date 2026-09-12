package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A rendered statement, the bindings its markers expect in allocation order, and the alias each
 * table instance was given in it — hydration reads what rendering assigned rather than deriving it
 * again.
 *
 * <p>{@link #preview()} answers "what will this send" without the library logging anything, so the
 * consumer keeps their own log policy.
 */
public record RenderedStatement(
    String sql, List<Binding> bindings, StatementAliases aliases) {

  private static final String REDACTED = "<redacted>";

  public RenderedStatement {
    Objects.requireNonNull(sql, "sql");
    Objects.requireNonNull(aliases, "aliases");
    Objects.requireNonNull(bindings, "bindings");

    bindings = List.copyOf(bindings);
  }

  /** The expected values in marker order, as a positional binder consumes them. */
  public List<Object> values() {
    return bindings.stream().map(Binding::value).toList();
  }

  /**
   * The statement and its markers, with values withheld. Safe to log: printing bind values would
   * turn every filtered query into a record of whatever it filtered on.
   */
  public String preview() {
    return previewWith(binding -> REDACTED);
  }

  /** The same, showing the values, for somewhere their disclosure is already acceptable. */
  public String previewWithValues() {
    return previewWith(binding -> String.valueOf(binding.value()));
  }

  private String previewWith(Function<Binding, String> shownValue) {
    StringBuilder description = new StringBuilder(sql);

    for (Binding binding : bindings) {
      description.append(System.lineSeparator())
          .append("  ")
          .append(binding.marker())
          .append(" = ")
          .append(shownValue.apply(binding));
    }

    return description.toString();
  }
}
