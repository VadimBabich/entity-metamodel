package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Two ways of saying transient through house annotations, one nested inside the other. */
@Table("composed_transients")
public class ComposedTransients {

  @Id
  @Column("row_id")
  Long id;

  @Column("kept")
  String kept;

  @MyTransient
  String oneLevel;

  @Computed
  String twoLevel;
}
