package com.example.diagnostics;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Relationships without {@code @MappedCollection} — the annotation is optional in Spring Data
 * Relational, so neither property is a column of this table even though nothing marks them.
 */
@Table("parent_aggregates")
public class ParentAggregate {

  @Id
  @Column("parent_id")
  Long id;

  @Column("label")
  String label;

  List<ChildAggregate> children;

  ChildAggregate soleChild;
}
