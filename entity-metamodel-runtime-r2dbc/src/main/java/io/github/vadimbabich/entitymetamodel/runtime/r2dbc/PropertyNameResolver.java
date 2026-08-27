package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import java.util.Objects;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;

/**
 * Turns a property name that arrived as text into a ref on a table instance. Only two doors need
 * it — a {@code Pageable}'s sort and an externally built filter — because everywhere else a name is
 * a generated constant.
 */
final class PropertyNameResolver {

  private final RelationalMappingContext mappingContext;

  PropertyNameResolver(RelationalMappingContext mappingContext) {
    this.mappingContext = Objects.requireNonNull(mappingContext, "mappingContext");
  }

  // The declared type comes from the context, so a resolved ref is the ref the generated constant
  // would have been.
  <E> PropertyRef<E, ?> resolve(EntityRef<E> instance, String propertyName) {
    Objects.requireNonNull(instance, "instance");
    Objects.requireNonNull(propertyName, "propertyName");

    RelationalPersistentEntity<?> persistentEntity =
        mappingContext.getRequiredPersistentEntity(instance.entityType());
    RelationalPersistentProperty persistentProperty =
        persistentEntity.getPersistentProperty(propertyName);

    if (persistentProperty == null) {
      throw new IllegalArgumentException(
          instance.entityType().getSimpleName() + " persists no property named '" + propertyName
              + "'. A name arriving as text is resolved against the entity's own properties — not"
              + " against a column name, and not along a path through a join");
    }

    return instance.property(propertyName, persistentProperty.getType());
  }
}
