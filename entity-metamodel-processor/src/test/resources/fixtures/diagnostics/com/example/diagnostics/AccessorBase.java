package com.example.diagnostics;

import org.springframework.data.relational.core.mapping.Column;

/** Declares the field and a plain accessor; the subclass overrides the accessor to annotate it. */
public class AccessorBase {

  @Column("inherited")
  String inherited;

  public String getInherited() {
    return inherited;
  }
}
