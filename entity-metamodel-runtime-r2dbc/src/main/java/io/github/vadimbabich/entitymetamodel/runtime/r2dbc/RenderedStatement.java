package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import java.util.List;
import java.util.Objects;

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

  /**
   * The expected values in marker order, as a positional binder consumes them.
   */
  public List<Object> values() {
    return bindings.stream().map(Binding::value).toList();
  }

  /**
   * The statement and its markers, with values withheld. Safe to log: printing bind values would
   * turn every filtered query into a record of whatever it filtered on.
   */
  public String preview() {
    StringBuilder description = new StringBuilder(sql);

    for (Binding binding : bindings) {
      appendMarker(description, binding.marker(), REDACTED);
    }

    return description.toString();
  }

  /**
   * The same, showing the values, for somewhere their disclosure is already acceptable.
   */
  public String previewWithValues() {
    StringBuilder description = new StringBuilder(sql);

    for (Binding binding : bindings) {
      appendMarker(description, binding.marker(), String.valueOf(binding.value()));
    }

    return description.toString();
  }

  private static void appendMarker(
      StringBuilder description, String marker, String shownValue) {

    description.append(System.lineSeparator())
        .append("  ")
        .append(marker)
        .append(" = ")
        .append(shownValue);
  }
}
