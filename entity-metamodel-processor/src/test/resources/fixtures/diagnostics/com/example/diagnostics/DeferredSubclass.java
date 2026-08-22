package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Its *supertype* is the thing another processor generates: unresolved in round 1. */
@Table("deferred_subclasses")
public class DeferredSubclass extends GeneratedLater {

  @Id
  @Column("row_id")
  Long id;
}
