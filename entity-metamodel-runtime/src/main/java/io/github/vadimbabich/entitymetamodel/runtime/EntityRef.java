package io.github.vadimbabich.entitymetamodel.runtime;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;

/**
 * Immutable, instance-scoped handle to an entity type. The default instance and any
 * {@link #as(String) aliased copy} are distinct table instances: every name in a statement derives
 * from the instance's alias, never from the class.
 */
public final class EntityRef<E> {

  /**
   * Reserved projected-label separator ({@code <tableAlias>__<column>}); forbidden inside aliases
   * because a duplicate label makes R2DBC's {@code Row.get(String)} silently return the first match.
   */
  static final String PROJECTION_SEPARATOR = "__";

  /**
   * What a caller-supplied alias qualifier may contain. The alias is rendered as a bare identifier,
   * so this is the one place caller text reaches the statement unmediated — property and table names
   * both resolve through the mapping context, which rejects what it does not know.
   */
  private static final Pattern IDENTIFIER_SAFE = Pattern.compile("[A-Za-z0-9_]+");

  private final Class<E> entityType;
  private final String alias;

  private EntityRef(Class<E> entityType, String alias) {
    this.entityType = entityType;
    this.alias = alias;
  }

  /** The default instance, aliased with the lower-cased entity simple name. */
  public static <E> EntityRef<E> of(Class<E> entityType) {
    Objects.requireNonNull(entityType, "entityType");

    String defaultAlias = defaultAliasOf(entityType);
    rejectReservedSeparator(defaultAlias, "entity simple name");

    return new EntityRef<>(entityType, defaultAlias);
  }

  /**
   * A distinct instance of the same entity, aliased {@code <thisAlias>_<qualifier>}. Re-aliasing
   * extends the alias rather than replacing the qualifier, so two instances cannot collide.
   *
   * <p>The qualifier is lower-cased. A database folds an unquoted alias, so qualifiers differing
   * only in case are one instance there; keeping them distinct here would mint two refs that both
   * claim the same projected labels.
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
   * A typed property handle. {@code T} is the compile-time contract; the token records the declared
   * form and is carried for diagnostics only.
   *
   * <p>Deliberately {@code Class<?>} and not {@code Class<T>}: a generated metamodel pairs
   * {@code List.class} with {@code List<String>}, and an erased token cannot unify with the
   * parameterized contract it stands for.
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

  /**
   * The projected label for one of this instance's columns, {@code <alias>__<column>}. It lives here
   * because the constructor is what keeps {@link #PROJECTION_SEPARATOR} out of the alias, which is
   * the only reason the label can be split back apart.
   */
  public String projectedLabel(String columnName) {
    Objects.requireNonNull(columnName, "columnName");

    return projectedLabelPrefix() + columnName;
  }

  /**
   * What every one of this instance's projected labels begins with. Reading a row back tests
   * against this rather than against the bare alias: one alias can prefix another
   * ({@code account} and {@code account_2}), and without the separator the shorter one claims the
   * longer one's columns.
   */
  public String projectedLabelPrefix() {
    return alias + PROJECTION_SEPARATOR;
  }

  /** Resolves the table name through the context — never re-implemented or cached here. */
  public String tableName(RelationalMappingContext mappingContext) {
    Objects.requireNonNull(mappingContext, "mappingContext");

    return mappingContext.getRequiredPersistentEntity(entityType).getTableName().getReference();
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
