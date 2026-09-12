package io.github.vadimbabich.entitymetamodel.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The frontend's complete payload. Entities are ordered by qualified name, so output that
 * aggregates across them is deterministic by construction, not by accident of discovery order.
 */
public record EntityModel(List<EntityDescriptor> entities) {

  public EntityModel {
    Objects.requireNonNull(entities, "entities");

    List<EntityDescriptor> sorted = new ArrayList<>(entities);
    sorted.sort(Comparator.comparing(EntityDescriptor::qualifiedName));
    entities = List.copyOf(sorted);
  }

  public static EntityModel of(List<EntityDescriptor> entities) {
    return new EntityModel(entities);
  }
}
