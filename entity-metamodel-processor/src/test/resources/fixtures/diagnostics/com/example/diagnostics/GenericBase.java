package com.example.diagnostics;

import org.springframework.data.relational.core.mapping.Column;

/** A generic superclass whose payload type is decided by whoever extends it. */
public class GenericBase<T> {

  @Column("payload")
  private T payload;

  @Column("label")
  String label;
}
