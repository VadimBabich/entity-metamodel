package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import java.util.Iterator;

import org.springframework.data.relational.core.mapping.RelationalPersistentEntity;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.util.StringUtils;

/**
 * Resolves the relation an entity names, which is the one place this module reads an identifier's
 * characters rather than assembling an AST from it.
 */
final class TableNameResolver {

  private TableNameResolver() {
  }

  // Qualified, not getTableName: that one drops the schema of a @Table(schema=...) entity. The dot
  // split below is this library's own — the mapping context keeps the legacy @Table("schema.table")
  // spelling as one identifier, which quoted whole names a relation with a dot in it. A declared
  // schema attribute turns the split off, which is the escape hatch for a name that really contains
  // a dot; a name the split cannot group goes over as spelled, since the database may still resolve
  // it and any grouping here would be a guess.
  //
  // The analysis runs on the entity's own name rather than the qualified one. A naming strategy's
  // default schema makes every qualified name composite, and analysing that would stack the default
  // on top of a name that already carries a schema, naming one part more than any database has.
  static SqlIdentifier resolve(RelationalPersistentEntity<?> persistentEntity) {
    SqlIdentifier qualified = persistentEntity.getQualifiedTableName();

    if (declaresSchema(persistentEntity)) {
      return qualified;
    }

    SqlIdentifier tableName = persistentEntity.getTableName();

    if (!carriesItsOwnName(tableName)) {
      return qualified;
    }

    String reference = tableName.getReference();
    int separator = reference.indexOf('.');

    if (separator < 0) {
      return qualified;
    }

    boolean severalDots = separator != reference.lastIndexOf('.');

    // Content, not position: a whitespace-only side clears an index check but not the substrate's
    // own Assert.hasText, which would then fail from a builder naming neither entity nor table.
    boolean blankPart =
        !StringUtils.hasText(reference.substring(0, separator))
            || !StringUtils.hasText(reference.substring(separator + 1));

    if (severalDots || blankPart) {
      return tableName;
    }

    // transform, never SqlIdentifier.quoted/unquoted: it keeps the concrete type and its quoting
    // flag, so forceQuote and a derived name's letter-case standardization survive the split.
    SqlIdentifier schema = tableName.transform(name -> name.substring(0, separator));
    SqlIdentifier table = tableName.transform(name -> name.substring(separator + 1));

    return SqlIdentifier.from(schema, table);
  }

  // hasText because that is what the mapping context applies to this attribute. Answering
  // differently for a blank value would suppress the split while the context still applies its
  // default schema.
  private static boolean declaresSchema(RelationalPersistentEntity<?> persistentEntity) {
    Table declaration = persistentEntity.findAnnotation(Table.class);

    if (declaration == null) {
      return false;
    }

    return StringUtils.hasText(declaration.schema());
  }

  // Identity, not part count: a composite of one part also counts one, yet refuses transform and,
  // when empty, getReference.
  static boolean carriesItsOwnName(SqlIdentifier identifier) {
    Iterator<SqlIdentifier> parts = identifier.iterator();

    if (!parts.hasNext()) {
      return false;
    }

    boolean yieldsItself = parts.next() == identifier;

    return yieldsItself && !parts.hasNext();
  }
}
