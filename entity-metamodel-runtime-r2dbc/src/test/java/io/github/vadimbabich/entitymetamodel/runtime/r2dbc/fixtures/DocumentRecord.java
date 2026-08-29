package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Raw-door fixture: its table carries a {@code jsonb} column deliberately left unmapped, so a
 * fragment can name it while the projection stays one column wide. Mapping it would drag jsonb
 * decoding into tests that prove binding; putting it on a shared fixture would rewrite the golden
 * SELECT lists of unrelated statements.
 */
@Table("document_records")
public class DocumentRecord {

  @Id
  @Column("id")
  public Long id;
}
