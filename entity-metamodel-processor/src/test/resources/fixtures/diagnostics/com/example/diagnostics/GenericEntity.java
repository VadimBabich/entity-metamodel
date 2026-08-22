package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** A generic entity type: its own name cannot be written without raw-type warnings. */
@Table("generic_entities")
public class GenericEntity<T> {

  @Id
  @Column("generic_id")
  Long id;
}
