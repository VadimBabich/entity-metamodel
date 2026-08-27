package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;

/**
 * Decides what the tables of one statement are called, and what no database will accept as an
 * identifier — the one part of rendering that touches no substrate SQL type.
 */
final class AliasScheme {

  // PostgreSQL's limit, applied to every dialect: it is the strictest of the supported four and the
  // only one that truncates silently rather than failing, so erring early costs readability and
  // never correctness. A per-dialect table waits on a dialect whose readability it would buy.
  private static final int MAX_IDENTIFIER_BYTES = 63;

  private final RelationalMappingContext mappingContext;

  AliasScheme(RelationalMappingContext mappingContext) {
    this.mappingContext = Objects.requireNonNull(mappingContext, "mappingContext");
  }

  /**
   * Positional aliases for the whole statement as soon as one label would not fit, since a
   * truncated label and its neighbour become one column in the row. Whether a label that overflows
   * even then is an error is decided where labels are emitted, not here.
   */
  StatementAliases forInstances(List<EntityRef<?>> instances) {
    for (EntityRef<?> instance : instances) {
      if (!everyLabelFits(instance, instance.alias())) {
        return StatementAliases.positional(instances);
      }
    }

    return StatementAliases.declared(instances);
  }

  /**
   * Refuses a label no table alias can rescue: a positional alias is the shortest prefix available.
   * The caller names what it was projecting; the limit stays here.
   */
  void rejectUnrenderableLabel(String label, String projected) {
    if (byteLength(label) <= MAX_IDENTIFIER_BYTES) {
      return;
    }

    throw new IllegalArgumentException(
        "Cannot project " + projected + ": its label '" + label + "' is " + byteLength(label)
            + " bytes, past the " + MAX_IDENTIFIER_BYTES + "-byte identifier limit under this"
            + " statement's table aliases, so a database would truncate it and return two columns"
            + " under one name. Shorten the column name, or project this instance in a statement"
            + " that names fewer tables");
  }

  // Over every column, projected by this statement or not, so a page and its count cannot disagree
  // about aliases. Properties that are not columns are skipped: projecting is where that refusal
  // belongs, and a count has no projection to refuse.
  private boolean everyLabelFits(EntityRef<?> instance, String alias) {
    // Prefix-plus-column arithmetic rather than building each label, since this runs per column per
    // instance on every render. A prefix that does not fit alone leaves no room for any column.
    int prefixBytes = byteLength(alias) + byteLength(EntityRef.PROJECTION_SEPARATOR);
    if (prefixBytes > MAX_IDENTIFIER_BYTES) {
      return false;
    }

    RelationalPersistentEntity<?> persistentEntity =
        mappingContext.getRequiredPersistentEntity(instance.entityType());

    for (RelationalPersistentProperty property : persistentEntity) {
      if (property.isEmbedded() || property.isEntity()) {
        continue;
      }

      int columnBytes = byteLength(property.getColumnName().getReference());
      if (prefixBytes + columnBytes > MAX_IDENTIFIER_BYTES) {
        return false;
      }
    }

    return true;
  }

  private static int byteLength(String identifier) {
    // Bytes, not characters: the limit is a byte limit, and one non-ASCII character costs more.
    return identifier.getBytes(StandardCharsets.UTF_8).length;
  }
}
