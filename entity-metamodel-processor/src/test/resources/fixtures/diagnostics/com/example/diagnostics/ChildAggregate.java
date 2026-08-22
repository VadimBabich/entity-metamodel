package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Its own aggregate, its own table — so a reference to it is never a column. */
@Table("child_aggregates")
public class ChildAggregate {

  @Id
  @Column("child_id")
  Long id;
}
