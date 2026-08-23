package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.r2dbc.convert.R2dbcConverter;
import org.springframework.data.relational.domain.RowDocument;

/**
 * One row of a multi-entity projection, read back one table instance at a time.
 *
 * <p>Keyed by instance rather than by class, which is what makes two projections of the same table
 * readable at all: reading by column name gives both instances the same labels, and the second
 * silently receives the first one's values.
 */
public final class ProjectedRow {

  private final Map<String, Object> projectedColumns;
  private final R2dbcConverter converter;

  ProjectedRow(Map<String, Object> projectedColumns, R2dbcConverter converter) {
    this.projectedColumns = Objects.requireNonNull(projectedColumns, "projectedColumns");
    this.converter = Objects.requireNonNull(converter, "converter");
  }

  /**
   * The instance's entity, which must be present in this row.
   *
   * <p>Use {@link #readOptional(EntityRef)} for anything reached through an outer join: a row that
   * matched nothing on that side carries the instance's columns as SQL NULL, and there is no entity
   * to build from them.
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

  /** The instance's entity, or empty when this row matched nothing on that side. */
  public <E> Optional<E> readOptional(EntityRef<E> instance) {
    Objects.requireNonNull(instance, "instance");

    RowDocument document = new ProjectedColumns(instance).documentFrom(projectedColumns);

    // Every column null means the join found no counterpart. Hydrating that would produce an
    // entity with a null identity, which reads as a real object everywhere it is passed.
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
