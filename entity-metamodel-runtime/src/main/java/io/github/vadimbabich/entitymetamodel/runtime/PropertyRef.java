package io.github.vadimbabich.entitymetamodel.runtime;

import java.util.Objects;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;

/**
 * Immutable, typed handle to one entity property on one {@link EntityRef table instance}. Equality
 * is value identity over {@code (entityType, propertyName, alias)}; SQL names resolve on demand
 * through a mapping context and are never cached here.
 */
public final class PropertyRef<E, T> {

  private final EntityRef<E> entity;
  private final String propertyName;
  private final Class<?> declaredRawType;

  PropertyRef(EntityRef<E> entity, String propertyName, Class<?> declaredRawType) {
    this.entity = entity;
    this.propertyName = propertyName;
    this.declaredRawType = declaredRawType;
  }

  /** The Java property name — the drop-in replacement for every {@code nameOf(...)} argument. */
  public String name() {
    return propertyName;
  }

  public EntityRef<E> entity() {
    return entity;
  }

  public Class<?> declaredRawType() {
    return declaredRawType;
  }

  /** Re-anchors this property to another instance of the same entity (self-join reads). */
  public PropertyRef<E, T> of(EntityRef<E> instance) {
    Objects.requireNonNull(instance, "instance");

    return new PropertyRef<>(instance, propertyName, declaredRawType);
  }

  @SuppressWarnings("unused")
  public Predicate is(T value) {
    throw new UnsupportedOperationException("Predicate building requires the r2dbc module");
  }

  @SafeVarargs
  @SuppressWarnings("unused")
  public final Predicate in(T... values) {
    throw new UnsupportedOperationException("Predicate building requires the r2dbc module");
  }

  @SuppressWarnings("unused")
  public Predicate isNull() {
    throw new UnsupportedOperationException("Predicate building requires the r2dbc module");
  }

  @SuppressWarnings("unused")
  public Predicate gt(T value) {
    throw new UnsupportedOperationException("Predicate building requires the r2dbc module");
  }

  @SuppressWarnings("unused")
  public Predicate gte(T value) {
    throw new UnsupportedOperationException("Predicate building requires the r2dbc module");
  }

  @SuppressWarnings("unused")
  public Predicate lt(T value) {
    throw new UnsupportedOperationException("Predicate building requires the r2dbc module");
  }

  @SuppressWarnings("unused")
  public Predicate lte(T value) {
    throw new UnsupportedOperationException("Predicate building requires the r2dbc module");
  }

  @SuppressWarnings("unused")
  public Predicate like(T pattern) {
    throw new UnsupportedOperationException("Predicate building requires the r2dbc module");
  }

  /**
   * Resolves the column name through the context, refusing anything that is not a column of the
   * entity's own table. Pass the application's configured context: a bare
   * {@code new RelationalMappingContext()} knows no dialect simple types and fails inside Spring on
   * a {@code BigDecimal} or {@code UUID} first.
   *
   * <p>A collection of simple values ({@code List<String>}) is the one case this cannot answer — an
   * array column on one dialect, an element table on another — so treat the name it returns as
   * unverified.
   */
  public String columnName(RelationalMappingContext mappingContext) {
    Objects.requireNonNull(mappingContext, "mappingContext");

    RelationalPersistentEntity<?> persistentEntity =
        mappingContext.getRequiredPersistentEntity(entity.entityType());
    RelationalPersistentProperty persistentProperty =
        persistentEntity.getPersistentProperty(propertyName);

    if (persistentProperty == null) {
      throw new IllegalArgumentException(
          describe() + " is not persistent in this mapping context");
    }

    // Neither an embedded value nor a relationship is a column, and the context calls both
    // entities — so they need separate answers, or one of them sends the reader to the wrong table.
    // The context is asked rather than the type inspected, so a value type the consumer converts to
    // a single column still resolves.
    if (persistentProperty.isEmbedded()) {
      throw new IllegalArgumentException(
          describe()
              + " is an embedded value, not a column: its own properties are the columns");
    }
    if (persistentProperty.isEntity()) {
      throw new IllegalArgumentException(
          describe()
              + " is not a column of this mapping context: it maps to another aggregate, whose"
              + " value lives in the referenced table. A value type that converts to one column"
              + " resolves once the context knows it as a simple type");
    }

    return persistentProperty.getColumnName().getReference();
  }

  private String describe() {
    return "Property '" + propertyName + "' of entity '"
        + entity.entityType().getSimpleName() + "'";
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof PropertyRef<?, ?> otherRef)) {
      return false;
    }

    return entity.equals(otherRef.entity) && propertyName.equals(otherRef.propertyName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(entity, propertyName);
  }

  @Override
  public String toString() {
    return "PropertyRef[" + entity.entityType().getSimpleName() + "." + propertyName
        + " on " + entity.alias() + "]";
  }
}
