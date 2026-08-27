package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Identifier-limit fixture past what the positional fallback can rescue: both column names are 62
 * bytes — legal in PostgreSQL, whose own limit is 63 — and they agree in their first 59, so even a
 * two-byte table alias plus the separator pushes both labels over the limit and onto the same
 * truncation.
 */
@Table("long_column_records")
public class LongColumnRecord {

  @Id
  @Column("id")
  public Long id;

  @Column("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa_x1")
  public String first;

  @Column("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa_x2")
  public String second;
}
