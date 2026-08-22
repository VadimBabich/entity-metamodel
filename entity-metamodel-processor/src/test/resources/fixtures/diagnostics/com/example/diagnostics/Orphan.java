package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("orphans")
public class Orphan extends NoSuchBase {

  @Id
  @Column("orphan_id")
  Long id;
}
