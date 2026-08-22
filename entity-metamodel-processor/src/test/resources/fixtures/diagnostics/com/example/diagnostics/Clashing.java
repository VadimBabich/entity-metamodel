package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Its metamodel name is already taken by a hand-written class in the same package. */
@Table("clashing")
public class Clashing {

  @Id
  @Column("clashing_id")
  Long id;
}
