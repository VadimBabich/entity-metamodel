package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.data.projection.EntityProjection;
import org.springframework.data.projection.ProjectionFactory;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;
import org.springframework.data.r2dbc.convert.R2dbcConverter;
import org.springframework.data.relational.domain.RowDocument;

/**
 * Decides once whether a result type may be projected from an instance, and holds the descriptor
 * the reader needs. Keyed on the (result type, entity) pair, since one projection interface may
 * serve several entities.
 */
final class ProjectionResolver {

  private final R2dbcConverter converter;
  private final ProjectionFactory projections = new SpelAwareProxyProjectionFactory();
  private final Map<ProjectedType, EntityProjection<?, ?>> accepted = new ConcurrentHashMap<>();

  ProjectionResolver(R2dbcConverter converter) {
    this.converter = Objects.requireNonNull(converter, "converter");
  }

  // Introspection runs outside the map rather than inside computeIfAbsent, which would hold a bin
  // lock across it. Deciding a pair twice under a race is harmless: the answer is the same.
  EntityProjection<?, ?> acceptedProjectionOf(EntityRef<?> instance, Class<?> resultType) {
    ProjectedType projected = new ProjectedType(resultType, instance.entityType());

    EntityProjection<?, ?> alreadyAccepted = accepted.get(projected);

    if (alreadyAccepted != null) {
      return alreadyAccepted;
    }

    EntityProjection<?, ?> projection = introspectAndAccept(instance, resultType);
    accepted.putIfAbsent(projected, projection);

    return projection;
  }

  private EntityProjection<?, ?> introspectAndAccept(EntityRef<?> instance, Class<?> resultType) {
    EntityProjection<?, ?> projection =
        converter.introspectProjection(resultType, instance.entityType());

    if (!projection.isProjection()) {
      throw new IllegalArgumentException(
          resultType.getName() + " is not a projection of " + instance.entityType().getSimpleName()
              + "; read the whole entity with read(...) or readOptional(...), or declare an"
              + " interface or DTO naming the properties you want");
    }

    rejectBypassedEntityConverter(instance, resultType);

    // Only interfaces are validated: their accessors are the whole of what they read. A DTO's
    // fields may carry their own @Column, bind without accessors, or be computed locally, and
    // every rule tried for telling those apart refused a shape the substrate reads.
    if (!resultType.isInterface()) {
      return projection;
    }

    if (!projection.isClosedProjection()) {
      throw notReadableFromColumns(resultType);
    }

    List<PropertyDescriptor> accessors =
        projections.getProjectionInformation(resultType).getInputProperties();

    rejectUnresolvedAccessors(projection, instance, resultType, accessors);

    return projection;
  }

  // An interface reports itself as not closed for two unrelated reasons, and naming the wrong one
  // sends the reader hunting for a mistake they did not make. findAnnotation, not
  // isAnnotationPresent: the substrate decides openness the same way, so a meta-annotated @Value
  // has to land on this branch rather than on the accessor-style one.
  private static IllegalArgumentException notReadableFromColumns(Class<?> resultType) {
    for (Method declared : resultType.getMethods()) {
      if (AnnotationUtils.findAnnotation(declared, Value.class) != null) {
        return new IllegalArgumentException(
            resultType.getName() + " is an open projection: " + declared.getName() + "() takes its"
                + " value from a @Value expression rather than from a column, which this door does"
                + " not evaluate. Declare the accessors over properties, or read the entity and"
                + " compute in the mapper");
      }
    }

    return new IllegalArgumentException(
        resultType.getName() + " declares no readable property, so there is nothing to project;"
            + " a projection accessor is JavaBean-style — getOwnerEmail(), not ownerEmail()");
  }

  // Projecting cannot consult a converter that builds the whole entity, so the two doors would
  // answer differently about one row — and where that converter redacts a column, projecting leaks.
  private void rejectBypassedEntityConverter(EntityRef<?> instance, Class<?> resultType) {
    boolean entityHasItsOwnConverter =
        converter.getConversionService().canConvert(RowDocument.class, instance.entityType());

    if (!entityHasItsOwnConverter) {
      return;
    }

    throw new IllegalArgumentException(
        instance.entityType().getSimpleName() + " is built by a registered converter, which"
            + " projecting cannot apply — " + resultType.getName() + " would be read straight from"
            + " the row and could disagree with the entity. Read the entity with read(...) and take"
            + " what you need from it in the mapper");
  }

  // An interface has no compile-time link to the entity, so a stale accessor still compiles and
  // the substrate answers null for it on every row.
  private void rejectUnresolvedAccessors(
      EntityProjection<?, ?> projection,
      EntityRef<?> instance,
      Class<?> resultType,
      List<PropertyDescriptor> accessors) {

    List<String> unresolved = new ArrayList<>();

    for (PropertyDescriptor accessor : accessors) {
      if (projection.findProperty(accessor.getName()) == null) {
        unresolved.add(accessor.getName());
      }
    }

    if (unresolved.isEmpty()) {
      return;
    }

    throw new IllegalArgumentException(
        resultType.getName() + " names " + unresolved + ", which "
            + instance.entityType().getSimpleName() + " does not persist; those accessors would"
            + " read null on every row. Correct the names, or drop them from the projection");
  }

  private record ProjectedType(Class<?> resultType, Class<?> entityType) {
  }
}
