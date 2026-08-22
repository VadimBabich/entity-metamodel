package com.example.diagnostics;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** The same relationship twice, once behind a wildcard bound. */
@Table("wildcard_parents")
public class WildcardParent {

  @Id
  @Column("parent_id")
  Long id;

  List<ChildAggregate> plainKids;

  List<? extends ChildAggregate> wildcardKids;
}
