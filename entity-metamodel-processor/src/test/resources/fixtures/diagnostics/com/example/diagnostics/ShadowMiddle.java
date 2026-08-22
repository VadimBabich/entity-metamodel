package com.example.diagnostics;

import org.springframework.data.relational.core.mapping.Column;

/** The nearer declaration, with an incompatible type — the entity itself declares neither. */
public class ShadowMiddle extends ShadowBase {

  @Column("label")
  Long label;
}
