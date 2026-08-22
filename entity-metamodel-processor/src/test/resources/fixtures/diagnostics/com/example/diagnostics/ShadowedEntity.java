package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** A property type whose simple name is the entity's own — importing it would rebind the entity. */
@Table("shadowed_entities")
public class ShadowedEntity {

  @Id
  @Column("shadowed_id")
  Long id;

  @Column("twin")
  com.example.diagnostics.other.ShadowedEntity twin;
}
