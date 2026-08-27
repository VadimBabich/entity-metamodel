package io.github.vadimbabich.entitymetamodel.runtime;

import java.util.Objects;

/**
 * Sorting by a raw expression — the escape hatch for an order the typed vocabulary cannot say. It
 * carries an {@link SqlExpr} rather than a bare string so the fragment can name a property instead
 * of an alias the builder owns and may reassign.
 */
public record ExpressionSort(SqlExpr expression, Direction direction) implements SortOrder {

  public ExpressionSort {
    Objects.requireNonNull(expression, "expression");
    Objects.requireNonNull(direction, "direction");
  }

  /**
   * <strong>The caller owns the safety of the fragment</strong> — see {@link SqlExpr#raw}. One
   * naming a column the query does not select is a dialect-dependent error, not a rejection here.
   */
  @RawSql
  public static ExpressionSort asc(SqlExpr expression) {
    return new ExpressionSort(expression, Direction.ASCENDING);
  }

  /**
   * Descending; the same warning applies as for {@link #asc(SqlExpr)}.
   */
  @RawSql
  public static ExpressionSort desc(SqlExpr expression) {
    return new ExpressionSort(expression, Direction.DESCENDING);
  }
}
