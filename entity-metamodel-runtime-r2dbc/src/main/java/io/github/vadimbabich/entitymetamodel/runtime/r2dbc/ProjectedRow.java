package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.data.projection.EntityProjection;
import org.springframework.data.r2dbc.convert.R2dbcConverter;
import org.springframework.data.relational.domain.RowDocument;

/**
 * One row of a multi-entity projection, read back one table instance at a time. Keyed by instance
 * rather than by class, which is what makes two projections of one table readable at all.
 *
 * <p>Instances are materialised from the row's own columns rather than through an entity template,
 * so only part of Spring's read machinery applies. Property-level reading converters fire on both
 * doors; {@code Converter<Row, T>} and {@code AfterConvertCallback} never fire at all. Logic that
 * has to run after an entity is read belongs in the mapper the executor takes.
 */
public final class ProjectedRow {

  private final Map<String, Object> projectedColumns;
  private final R2dbcConverter converter;
  private final StatementAliases aliases;
  private final ProjectionResolver projections;

  // The resolver must be built over the same converter: it introspects the descriptor this row
  // then applies, and two mapping contexts would resolve a column differently with nothing to fail.
  ProjectedRow(
      Map<String, Object> projectedColumns,
      R2dbcConverter converter,
      StatementAliases aliases,
      ProjectionResolver projections) {

    this.projectedColumns = Objects.requireNonNull(projectedColumns, "projectedColumns");
    this.converter = Objects.requireNonNull(converter, "converter");
    this.aliases = Objects.requireNonNull(aliases, "aliases");
    this.projections = Objects.requireNonNull(projections, "projections");
  }

  /**
   * The instance's entity, which must be present in this row. Use
   * {@link #readOptional(EntityRef)} for anything reached through an outer join, where a row that
   * matched nothing carries the instance's columns as SQL NULL.
   */
  public <E> E read(EntityRef<E> instance) {
    return readOptional(instance).orElseThrow(() -> absent(instance, "readOptional(...)"));
  }

  /**
   * The instance's entity, or empty when this row matched nothing on that side.
   */
  public <E> Optional<E> readOptional(EntityRef<E> instance) {
    Objects.requireNonNull(instance, "instance");

    RowDocument document = documentOf(instance);

    if (carriesNoValue(document)) {
      return Optional.empty();
    }

    return Optional.of(converter.read(instance.entityType(), document));
  }

  /**
   * The instance projected onto a closed interface or a DTO, which must be present in this row.
   * Use {@link #readProjectionOptional(EntityRef, Class)} for anything reached through an outer
   * join.
   */
  public <E, R> R readProjection(EntityRef<E> instance, Class<R> resultType) {
    return readProjectionOptional(instance, resultType)
        .orElseThrow(() -> absent(instance, "readProjectionOptional(...)"));
  }

  /**
   * The instance projected onto a closed interface or a DTO whose members name its properties.
   *
   * <p>The two forms narrow differently: a DTO reads only the properties it declares, an interface
   * projection reads and converts every property of the instance.
   *
   * <p>Interface projections are checked against the entity — an open {@code @Value} projection, or
   * an accessor naming something the entity does not persist, is refused; a DTO is bound by the
   * substrate unchecked, so a stale field reads null or its type's default while a stale primitive
   * constructor parameter fails. That check runs as a row is read, so a query matching nothing
   * never reaches it.
   *
   * <p>An entity built by a registered {@code Converter<RowDocument, T>} cannot be projected at
   * all: projecting reads properties directly, which would bypass that converter, so it is refused.
   */
  public <E, R> Optional<R> readProjectionOptional(EntityRef<E> instance, Class<R> resultType) {
    Objects.requireNonNull(instance, "instance");
    Objects.requireNonNull(resultType, "resultType");

    EntityProjection<?, ?> projection = projections.acceptedProjectionOf(instance, resultType);

    RowDocument document = documentOf(instance);

    if (carriesNoValue(document)) {
      return Optional.empty();
    }

    Object projected = converter.project(projection, document);

    return Optional.of(resultType.cast(projected));
  }

  private RowDocument documentOf(EntityRef<?> instance) {
    return ProjectedColumns.documentFrom(
        aliases.projectedLabelPrefix(instance), projectedColumns);
  }

  private static IllegalStateException absent(EntityRef<?> instance, String optionalForm) {
    return new IllegalStateException(
        instance + " matched no row here, so there is nothing to read; an outer join can leave an"
            + " instance absent, and " + optionalForm + " reports that");
  }

  // Every column null means the join found no counterpart. Exact while at least one projected
  // column is non-nullable; hydrating anyway yields an entity with a null identity.
  private static boolean carriesNoValue(RowDocument document) {
    for (Object value : document.values()) {
      if (value != null) {
        return false;
      }
    }

    return true;
  }
}
