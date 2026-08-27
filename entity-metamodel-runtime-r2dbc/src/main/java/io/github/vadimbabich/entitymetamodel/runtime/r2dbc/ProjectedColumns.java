package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import java.util.Map;
import java.util.Objects;
import org.springframework.data.relational.domain.RowDocument;

/**
 * Takes one table instance's columns out of a projected row and strips the prefix, so a converter
 * sees the entity's own column names. The boundary includes the separator: comparing against the
 * bare alias would let {@code account} claim {@code account_2}'s columns and look plausible.
 */
final class ProjectedColumns {

  private ProjectedColumns() {
  }

  // Static because there is no state worth holding, and this is asked once per instance per row.
  static RowDocument documentFrom(String prefix, Map<String, Object> projectedColumns) {
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(projectedColumns, "projectedColumns");

    RowDocument document = new RowDocument();

    for (Map.Entry<String, Object> projectedColumn : projectedColumns.entrySet()) {
      if (!projectedColumn.getKey().startsWith(prefix)) {
        continue;
      }

      document.put(
          projectedColumn.getKey().substring(prefix.length()), projectedColumn.getValue());
    }

    // Not an empty result: the statement projected something other than what this instance reads
    // back, and a blank entity would hide the mismatch.
    if (document.isEmpty()) {
      throw new IllegalStateException(
          "The row carries no column labelled '" + prefix + "...'. Joining an instance projects"
              + " nothing, so alsoSelect(instance) is needed before reading it; the row held "
              + projectedColumns.keySet());
    }

    return document;
  }
}
