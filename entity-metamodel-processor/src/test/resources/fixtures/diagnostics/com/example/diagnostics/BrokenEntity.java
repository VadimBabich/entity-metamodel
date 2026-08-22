package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** One member's type does not resolve: the compiler reports that, and no metamodel is generated. */
@Table("broken_entities")
public class BrokenEntity {

  @Id
  @Column("broken_id")
  Long id;

  @Column("payload")
  MissingType payload;
}
