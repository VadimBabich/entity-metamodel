package io.github.vadimbabich.entitymetamodel.runtime.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("children")
public class Child {

  @Id
  @Column("child_id")
  Long id;

  @Column("label")
  String label;
}
