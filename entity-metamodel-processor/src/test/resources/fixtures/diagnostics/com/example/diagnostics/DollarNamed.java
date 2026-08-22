package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** A legal field name containing '$' — a column, not a compiler artefact. */
@Table("dollar_named")
public class DollarNamed {

  @Id
  @Column("row_id")
  Long id;

  @Column("price_usd")
  Long price$usd;
}
