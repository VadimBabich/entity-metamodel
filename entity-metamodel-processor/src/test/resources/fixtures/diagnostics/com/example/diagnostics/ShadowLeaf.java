package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Inherits the clash without declaring either side of it. */
@Table("shadow_leaves")
public class ShadowLeaf extends ShadowMiddle {

  @Id
  @Column("leaf_id")
  Long id;
}
