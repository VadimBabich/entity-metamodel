package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.relational.domain.RowDocument;

/**
 * Takes one table instance's columns out of a projected row and strips the instance prefix, so a
 * converter sees the entity's own column names rather than the labels the statement projected.
 *
 * <p>The boundary includes the separator, and that is the whole point: one alias can prefix
 * another, so comparing against the bare alias lets {@code account} claim {@code account_2}'s
 * columns. The entity that came back would carry the other instance's values and look entirely
 * plausible.
 */
final class ProjectedColumns {

  private final String prefix;

  ProjectedColumns(EntityRef<?> instance) {
    Objects.requireNonNull(instance, "instance");

    this.prefix = instance.projectedLabelPrefix();
  }

  RowDocument documentFrom(Map<String, Object> projectedRow) {
    Objects.requireNonNull(projectedRow, "projectedRow");

    RowDocument document = new RowDocument();

    for (Map.Entry<String, Object> projectedColumn : projectedRow.entrySet()) {
      if (!projectedColumn.getKey().startsWith(prefix)) {
        continue;
      }

      document.put(
          projectedColumn.getKey().substring(prefix.length()), projectedColumn.getValue());
    }

    // A row with no matching label is not an empty result — the statement projected something
    // other than what this instance reads back, and a blank entity would hide the mismatch.
    if (document.isEmpty()) {
      throw new IllegalStateException(
          "The row carries no column labelled '" + prefix + "...'. Joining an instance projects"
              + " nothing, so alsoSelect(instance) is needed before reading it; the row held "
              + projectedRow.keySet());
    }

    return document;
  }
}
