package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** A property whose member name is the one the metamodel reserves for the entity handle. */
@Table("reserved_member_holders")
public class ReservedMemberHolder {

  @Id
  @Column("holder_id")
  Long id;

  @Column("entity")
  String entity;
}
