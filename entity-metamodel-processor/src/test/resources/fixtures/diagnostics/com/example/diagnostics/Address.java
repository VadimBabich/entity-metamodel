package com.example.diagnostics;

import org.springframework.data.relational.core.mapping.Column;

/** A value object, not an aggregate: no {@code @Table}, so no metamodel of its own. */
public class Address {

  @Column("street")
  String street;
}
