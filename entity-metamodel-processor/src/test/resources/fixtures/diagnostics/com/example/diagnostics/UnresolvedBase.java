package com.example.diagnostics;

import org.springframework.data.relational.core.mapping.Column;

/** A supertype holding a member whose type does not resolve in this compilation. */
public class UnresolvedBase {

  @Column("payload")
  MissingPayload payload;

  @Column("label")
  String label;
}
