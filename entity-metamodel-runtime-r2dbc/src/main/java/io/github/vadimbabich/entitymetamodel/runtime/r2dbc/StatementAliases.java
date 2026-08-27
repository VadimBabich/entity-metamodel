package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Which alias each table instance carries in one statement, and so which labels its columns are
 * projected under. Rendering assigns it and hydration reads it, so the two cannot disagree about
 * what a label means.
 */
public final class StatementAliases {

  private final Map<EntityRef<?>, String> aliasByInstance;
  private final Map<EntityRef<?>, String> labelPrefixByInstance;

  private StatementAliases(Map<EntityRef<?>, String> aliasByInstance) {
    this.aliasByInstance = aliasByInstance;

    // Once per statement, not per row: hydration asks for every instance of every row it reads.
    Map<EntityRef<?>, String> prefixes = new LinkedHashMap<>();
    for (Map.Entry<EntityRef<?>, String> assigned : aliasByInstance.entrySet()) {
      prefixes.put(assigned.getKey(), assigned.getValue() + EntityRef.PROJECTION_SEPARATOR);
    }

    this.labelPrefixByInstance = Map.copyOf(prefixes);
  }

  static StatementAliases declared(Collection<EntityRef<?>> instances) {
    Map<EntityRef<?>, String> aliases = new LinkedHashMap<>();

    for (EntityRef<?> instance : instances) {
      aliases.put(instance, instance.alias());
    }

    return new StatementAliases(Map.copyOf(aliases));
  }

  /**
   * Positional aliases in first-reference order, given to every instance and not only the ones that
   * overflow, so one description has one rendering.
   */
  static StatementAliases positional(Collection<EntityRef<?>> instances) {
    Map<EntityRef<?>, String> aliases = new LinkedHashMap<>();

    int position = 1;
    for (EntityRef<?> instance : instances) {
      aliases.put(instance, "t" + position);
      position++;
    }

    return new StatementAliases(Map.copyOf(aliases));
  }

  public String aliasOf(EntityRef<?> instance) {
    Objects.requireNonNull(instance, "instance");

    String alias = aliasByInstance.get(instance);

    if (alias == null) {
      throw notInStatement(instance);
    }

    return alias;
  }

  public String projectedLabel(EntityRef<?> instance, String columnName) {
    Objects.requireNonNull(columnName, "columnName");

    return projectedLabelPrefix(instance) + columnName;
  }

  /**
   * What every one of the instance's labels begins with. Reading a row tests against this, not the
   * alias alone: one alias can prefix another, and the shorter would claim the longer's columns.
   */
  public String projectedLabelPrefix(EntityRef<?> instance) {
    Objects.requireNonNull(instance, "instance");

    String prefix = labelPrefixByInstance.get(instance);

    if (prefix == null) {
      throw notInStatement(instance);
    }

    return prefix;
  }

  private static IllegalArgumentException notInStatement(EntityRef<?> instance) {
    return new IllegalArgumentException(
        instance + " is not part of this statement, so it carries no alias in it");
  }
}
