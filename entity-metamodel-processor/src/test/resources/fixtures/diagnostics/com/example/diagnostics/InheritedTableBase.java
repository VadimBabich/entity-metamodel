package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("inherited_table_bases")
public class InheritedTableBase {

  @Id
  @Column("base_id")
  Long id;
}
