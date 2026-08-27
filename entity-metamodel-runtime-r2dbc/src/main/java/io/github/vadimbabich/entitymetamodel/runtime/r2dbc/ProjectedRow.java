package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.r2dbc.convert.R2dbcConverter;
import org.springframework.data.relational.domain.RowDocument;

/**
 * One row of a multi-entity projection, read back one table instance at a time. Keyed by instance
 * rather than by class, which is what makes two projections of one table readable at all.
 */
public final class ProjectedRow {

  private final Map<String, Object> projectedColumns;
  private final R2dbcConverter converter;
  private final StatementAliases aliases;

  ProjectedRow(
      Map<String, Object> projectedColumns, R2dbcConverter converter, StatementAliases aliases) {

    this.projectedColumns = Objects.requireNonNull(projectedColumns, "projectedColumns");
    this.converter = Objects.requireNonNull(converter, "converter");
    this.aliases = Objects.requireNonNull(aliases, "aliases");
  }

  /**
   * The instance's entity, which must be present in this row. Use
   * {@link #readOptional(EntityRef)} for anything reached through an outer join, where a row that
   * matched nothing carries the instance's columns as SQL NULL.
   */
  public <E> E read(EntityRef<E> instance) {
    return readOptional(instance)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    instance
                        + " matched no row here, so there is nothing to read; an outer join can"
                        + " leave an instance absent, and readOptional(...) reports that"));
  }

  /**
   * The instance's entity, or empty when this row matched nothing on that side.
   */
  public <E> Optional<E> readOptional(EntityRef<E> instance) {
    Objects.requireNonNull(instance, "instance");

    RowDocument document =
        ProjectedColumns.documentFrom(aliases.projectedLabelPrefix(instance), projectedColumns);

    // Every column null means the join found no counterpart, and hydrating that produces an entity
    // with a null identity that reads as a real object everywhere it is passed.
    if (carriesNoValue(document)) {
      return Optional.empty();
    }

    return Optional.of(converter.read(instance.entityType(), document));
  }

  private static boolean carriesNoValue(RowDocument document) {
    for (Object value : document.values()) {
      if (value != null) {
        return false;
      }
    }

    return true;
  }
}
