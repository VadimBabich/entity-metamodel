package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Compiled beside a broken entity: its own metamodel must still be generated. */
@Table("healthy_entities")
public class HealthyEntity {

  @Id
  @Column("healthy_id")
  Long id;
}
