package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;

/** An entity declared through the stereotype rather than through @Table directly. */
@Aggregate
public class ComposedChild {

  @Id
  @Column("child_id")
  Long id;
}
