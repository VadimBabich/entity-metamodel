package io.github.vadimbabich.entitymetamodel.runtime;

import java.util.List;
import java.util.Objects;

/**
 * A raw SQL fragment used as a condition — the escape hatch for what the typed vocabulary cannot
 * express.
 *
 * <p>An owned type on purpose: handing back a substrate expression would put that substrate's types
 * into every signature the fragment touches, pinning the whole surface to one version of it.
 *
 * <p>The fragment is a <em>template</em>, never a place to put values. Each {@code ?} becomes one
 * of the statement's bind markers, so a value that happens to look like SQL stays a value.
 */
public record SqlExpr(String sql, List<?> bindings) implements Condition {

  private static final char PLACEHOLDER = '?';

  public SqlExpr {
    Objects.requireNonNull(sql, "sql");
    Objects.requireNonNull(bindings, "bindings");

    if (sql.isBlank()) {
      throw new IllegalArgumentException("A raw SQL fragment must not be blank");
    }

    bindings = List.copyOf(bindings);
    rejectPlaceholderMismatch(sql, bindings.size());
  }

  /**
   * A raw fragment and the values its {@code ?} placeholders stand for.
   *
   * <p><strong>The caller owns the safety of {@code sql}.</strong> Never assemble it from untrusted
   * input: identifiers and operators in the fragment reach the database verbatim. Values are safe
   * — pass every one of them as a binding rather than writing it into the fragment.
   */
  @RawSql
  public static SqlExpr raw(String sql, Object... bindings) {
    Objects.requireNonNull(bindings, "bindings");

    return new SqlExpr(sql, List.of(bindings));
  }

  private static void rejectPlaceholderMismatch(String sql, int bindingCount) {
    int placeholders = 0;
    for (int position = 0; position < sql.length(); position++) {
      if (sql.charAt(position) == PLACEHOLDER) {
        placeholders++;
      }
    }

    if (placeholders != bindingCount) {
      throw new IllegalArgumentException(
          "The fragment has " + placeholders + " placeholder(s) but " + bindingCount
              + " value(s) were given: " + sql
              + ". Every '?' counts as a placeholder, including one inside a string literal or a"
              + " jsonb operator such as '?|', which this door cannot yet express");
    }
  }
}
