package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Its nested entity's metamodel is named Inner__, and it also has a property of type Inner__. */
@Table("colliding_holders")
public class CollidingHolder {

  @Id
  @Column("holder_id")
  Long id;

  @Column("marker")
  Inner__ marker;

  @Table("inners")
  public record Inner(@Id @Column("inner_id") Long id) {
  }
}
