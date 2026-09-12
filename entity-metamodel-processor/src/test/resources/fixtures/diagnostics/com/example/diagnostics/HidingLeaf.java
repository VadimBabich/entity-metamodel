package com.example.diagnostics;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("hiding_leaves")
public class HidingLeaf extends HiddenBase {

  @Id
  @Column("leaf_id")
  Long id;

  List<ChildAggregate> items;
}
