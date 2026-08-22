package io.github.vadimbabich.entitymetamodel.runtime.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Embedded fixture: {@code total}'s columns live in this table, so it needs a different answer from
 * a relationship even though the mapping context calls both entities.
 */
@Table("invoices")
public class Invoice {

  @Id
  @Column("invoice_id")
  Long id;

  // The @Embedded.Nullable shortcut is meta-annotated with JSR-305's @Nonnull, which this build has
  // no jar for, and under -Werror that classfile warning fails the compile.
  @Embedded(onEmpty = Embedded.OnEmpty.USE_NULL)
  Money total;

  /** The same value type, not embedded: one column if the context converts it, otherwise not. */
  @Column("refund")
  Money refund;

  public record Money(long amount, String currency) {
  }
}
