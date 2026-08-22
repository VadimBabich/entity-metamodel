package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** The runtime reserves {@code __} as its projected-label separator, so this name cannot alias. */
@Table("odd_names")
public class Odd__Name {

  @Id
  @Column("odd_id")
  Long id;
}
