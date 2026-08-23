package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import java.util.List;
import java.util.Objects;

/**
 * A rendered statement and the bindings its markers expect, in allocation order.
 *
 * <p>The {@link #preview()} pair is the inspection door: it answers "what will this send" without
 * the library logging anything itself, so the consumer keeps their own log policy.
 */
public record RenderedStatement(String sql, List<Binding> bindings) {

  private static final String REDACTED = "<redacted>";

  public RenderedStatement {
    Objects.requireNonNull(sql, "sql");
    bindings = List.copyOf(bindings);
  }

  /** The expected values in marker order — what a positional binder consumes. */
  public List<Object> values() {
    return bindings.stream().map(Binding::value).toList();
  }

  /**
   * The statement and its markers, with values withheld. Safe to log: a preview is written far more
   * often than it is read, and printing bind values would turn every filtered query into a record
   * of whatever it filtered on.
   */
  public String preview() {
    StringBuilder description = new StringBuilder(sql);

    for (Binding binding : bindings) {
      appendMarker(description, binding.marker(), REDACTED);
    }

    return description.toString();
  }

  /** The same, showing the values — for somewhere their disclosure is already acceptable. */
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
