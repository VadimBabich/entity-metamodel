package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Waits for a type another processor generates: unresolvable in round 1, resolvable in round 2. */
@Table("deferred_entities")
public class DeferredEntity {

  @Id
  @Column("deferred_id")
  Long id;

  @Column("generated")
  GeneratedLater generated;
}
