package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("trailing")
public class Trailing_ {

  @Id
  @Column("id")
  Long id;
}
