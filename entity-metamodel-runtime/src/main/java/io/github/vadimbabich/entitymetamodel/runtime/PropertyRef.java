package io.github.vadimbabich.entitymetamodel.runtime;

import java.util.Collection;
import java.util.List;
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

  /** Equality against one value; {@code null} is rejected — use {@link #isNull()}. */
  public Condition is(T value) {
    return new Comparison(this, Comparison.Operator.EQUAL, value);
  }

  /**
   * Membership in a set of values. Takes a collection because that is what call sites hold.
   *
   * <p>Each value becomes one bind parameter, and drivers cap how many a statement may carry — so
   * a list sized by upstream data will eventually fail at execution, and degrades planning well
   * before that. Feed this a bounded set; a large one belongs in a temporary table or an
   * {@code = ANY(?)} through the raw door.
   */
  public Condition in(Collection<? extends T> values) {
    Objects.requireNonNull(values, "values");

    return new Inclusion(this, List.copyOf(values));
  }

  public Condition isNull() {
    return new NullCheck(this);
  }

  public Condition gt(T value) {
    return new Comparison(this, Comparison.Operator.GREATER_THAN, value);
  }

  public Condition gte(T value) {
    return new Comparison(this, Comparison.Operator.GREATER_THAN_OR_EQUAL, value);
  }

  public Condition lt(T value) {
    return new Comparison(this, Comparison.Operator.LESS_THAN, value);
  }

  public Condition lte(T value) {
    return new Comparison(this, Comparison.Operator.LESS_THAN_OR_EQUAL, value);
  }

  public Condition like(T pattern) {
    return new Comparison(this, Comparison.Operator.LIKE, pattern);
  }

  public SortOrder asc() {
    return new SortOrder(this, SortOrder.Direction.ASCENDING);
  }

  public SortOrder desc() {
    return new SortOrder(this, SortOrder.Direction.DESCENDING);
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
    // entities, so they need separate answers. The context is asked rather than the type inspected,
    // so a value type the consumer converts to one column still resolves.
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
