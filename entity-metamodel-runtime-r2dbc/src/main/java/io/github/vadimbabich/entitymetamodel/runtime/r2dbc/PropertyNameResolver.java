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
      throw unresolvable(instance, persistentEntity, propertyName);
    }

    return instance.property(propertyName, persistentProperty.getType());
  }

  // A column name is the near miss worth naming: an application arriving here resolves its own
  // column names today, so that is the mistake it will make, once per call site.
  private static IllegalArgumentException unresolvable(
      EntityRef<?> instance, RelationalPersistentEntity<?> persistentEntity, String name) {

    String entityName = instance.entityType().getSimpleName();

    for (RelationalPersistentProperty property : persistentEntity) {
      // Neither owns a column of this table: an embedded value spreads over several, and a
      // relationship's value lives in the referenced table.
      if (property.isEmbedded() || property.isEntity()) {
        continue;
      }

      if (property.getColumnName().getReference().equalsIgnoreCase(name)) {
        return new IllegalArgumentException(
            "'" + name + "' is the column of " + entityName + "'s property '" + property.getName()
                + "' — name the property instead. Names arriving as text resolve against the"
                + " entity's properties, never against columns, because one entity's column name"
                + " can be another property's name and would then filter the wrong column with"
                + " nothing to notice");
      }
    }

    return new IllegalArgumentException(
        entityName + " persists no property named '" + name + "'. A name arriving as text is"
            + " resolved against the entity's own properties — not against a column name, and not"
            + " along a path through a join");
  }
}
