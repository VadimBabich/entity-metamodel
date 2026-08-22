package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Its own members resolve; the one it inherits does not. */
@Table("inherits_unresolved")
public class InheritsUnresolved extends UnresolvedBase {

  @Id
  @Column("row_id")
  Long id;
}
