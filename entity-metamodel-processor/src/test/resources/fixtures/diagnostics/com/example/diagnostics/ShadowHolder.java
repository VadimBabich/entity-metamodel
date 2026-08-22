package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Its own package declares a class named Integer, so the simple name is already taken. */
@Table("shadow_holders")
public class ShadowHolder {

  @Id
  @Column("holder_id")
  Long id;

  @Column("count")
  java.lang.Integer count;
}
