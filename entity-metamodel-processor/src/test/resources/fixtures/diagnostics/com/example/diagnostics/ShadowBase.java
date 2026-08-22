package com.example.diagnostics;

import org.springframework.data.relational.core.mapping.Column;

/** Declares label as a String; a nearer supertype redeclares it as a Long. */
public class ShadowBase {

  @Column("label")
  String label;
}
