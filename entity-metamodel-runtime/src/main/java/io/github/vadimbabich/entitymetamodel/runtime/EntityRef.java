package io.github.vadimbabich.entitymetamodel.runtime;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, instance-scoped handle to an entity type. The default instance and any
 * {@link #as(String) aliased copy} are distinct table instances: every name in a statement derives
 * from the instance's alias, never from the class.
 */
public final class EntityRef<E> {

  /**
   * Reserved projected-label separator ({@code <tableAlias>__<column>}), forbidden inside aliases
   * because a duplicate label makes R2DBC's {@code Row.get(String)} return the first match. Public
   * because an execution module builds and splits those labels.
   */
  public static final String PROJECTION_SEPARATOR = "__";

  // The alias renders as a bare identifier, so this is the one place caller text reaches the
  // statement unmediated: property and table names both resolve through the mapping context.
  private static final Pattern IDENTIFIER_SAFE = Pattern.compile("[A-Za-z0-9_]+");

  private final Class<E> entityType;
  private final String alias;

  private EntityRef(Class<E> entityType, String alias) {
    this.entityType = entityType;
    this.alias = alias;
  }

  /**
   * The default instance, aliased with the lower-cased entity simple name.
   */
  public static <E> EntityRef<E> of(Class<E> entityType) {
    Objects.requireNonNull(entityType, "entityType");

    String defaultAlias = defaultAliasOf(entityType);
    rejectReservedSeparator(defaultAlias, "entity simple name");

    return new EntityRef<>(entityType, defaultAlias);
  }

  /**
   * A distinct instance of the same entity, aliased {@code <thisAlias>_<qualifier>} — re-aliasing
   * extends rather than replaces, so two instances cannot collide.
   *
   * <p>The qualifier is lower-cased: a database folds an unquoted alias, so two qualifiers
   * differing only in case would mint two refs claiming the same projected labels.
   */
  public EntityRef<E> as(String qualifier) {
    Objects.requireNonNull(qualifier, "qualifier");
    if (qualifier.isBlank()) {
      throw new IllegalArgumentException("Alias qualifier must not be blank");
    }
    if (!IDENTIFIER_SAFE.matcher(qualifier).matches()) {
      throw new IllegalArgumentException(
          "An alias qualifier becomes part of an unquoted SQL identifier, so it must be letters,"
              + " digits or underscore, but was '" + qualifier + "'");
    }

    String qualifiedAlias = alias + "_" + qualifier.toLowerCase(Locale.ROOT);
    rejectReservedSeparator(qualifiedAlias, "alias");

    return new EntityRef<>(entityType, qualifiedAlias);
  }

  /**
   * A typed property handle, where {@code T} is the compile-time contract and the token records the
   * declared form for diagnostics.
   *
   * <p>{@code Class<?>} and not {@code Class<T>} on purpose: a generated metamodel pairs
   * {@code List.class} with {@code List<String>}, and an erased token cannot unify with it.
   */
  public <T> PropertyRef<E, T> property(String propertyName, Class<?> declaredRawType) {
    Objects.requireNonNull(propertyName, "propertyName");
    if (propertyName.isBlank()) {
      throw new IllegalArgumentException("Property name must not be blank");
    }
    Objects.requireNonNull(declaredRawType, "declaredRawType");

    return new PropertyRef<>(this, propertyName, declaredRawType);
  }

  public Class<E> entityType() {
    return entityType;
  }

  public String alias() {
    return alias;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof EntityRef<?> otherRef)) {
      return false;
    }

    return entityType.equals(otherRef.entityType) && alias.equals(otherRef.alias);
  }

  @Override
  public int hashCode() {
    return Objects.hash(entityType, alias);
  }

  @Override
  public String toString() {
    return "EntityRef[" + entityType.getSimpleName() + " as " + alias + "]";
  }

  private static String defaultAliasOf(Class<?> entityType) {
    return entityType.getSimpleName().toLowerCase(Locale.ROOT);
  }

  private static void rejectReservedSeparator(String candidate, String what) {
    if (candidate.contains(PROJECTION_SEPARATOR)) {
      throw new IllegalArgumentException(
          "The " + what + " '" + candidate + "' contains the reserved separator '"
              + PROJECTION_SEPARATOR + "'");
    }
  }
}
