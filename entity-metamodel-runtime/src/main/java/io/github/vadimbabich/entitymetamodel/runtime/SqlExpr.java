package io.github.vadimbabich.entitymetamodel.runtime;

import java.util.List;
import java.util.Objects;

/**
 * A raw SQL fragment used as a condition — the escape hatch for what the typed vocabulary cannot
 * express. An owned type, so no substrate expression reaches a signature the fragment touches.
 *
 * <p>The fragment is a <em>template</em>, never a place to put values. Each {@code ?} consumes one
 * argument in order: a {@link PropertyRef} becomes that instance's qualified column, and anything
 * else becomes a bind marker. No driver can encode a ref, which is what makes the two safe to tell
 * apart by type.
 */
public record SqlExpr(String sql, List<Object> arguments) implements Condition {

  private static final char PLACEHOLDER = '?';

  public SqlExpr {
    Objects.requireNonNull(sql, "sql");
    Objects.requireNonNull(arguments, "arguments");

    if (sql.isBlank()) {
      throw new IllegalArgumentException("A raw SQL fragment must not be blank");
    }

    arguments = List.copyOf(arguments);
    rejectPlaceholderMismatch(sql, arguments.size());
  }

  /**
   * A raw fragment and the columns and values its {@code ?} placeholders stand for.
   *
   * <p><strong>The caller owns the safety of {@code sql}.</strong> Never assemble it from untrusted
   * input; pass every value as an argument rather than writing it into the fragment.
   */
  @RawSql
  public static SqlExpr raw(String sql, Object... arguments) {
    Objects.requireNonNull(arguments, "arguments");

    return new SqlExpr(sql, List.of(arguments));
  }

  private static void rejectPlaceholderMismatch(String sql, int argumentCount) {
    int placeholders = 0;
    for (int position = 0; position < sql.length(); position++) {
      if (sql.charAt(position) == PLACEHOLDER) {
        placeholders++;
      }
    }

    if (placeholders != argumentCount) {
      throw new IllegalArgumentException(
          "The fragment has " + placeholders + " placeholder(s) but " + argumentCount
              + " argument(s) were given: " + sql
              + ". Every '?' counts as a placeholder, including one inside a string literal or a"
              + " jsonb operator such as '?|', which this door cannot yet express");
    }
  }
}
