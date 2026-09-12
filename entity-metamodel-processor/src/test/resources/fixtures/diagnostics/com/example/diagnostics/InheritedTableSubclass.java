package com.example.diagnostics;

import org.springframework.data.relational.core.mapping.Column;

public class InheritedTableSubclass extends InheritedTableBase {

  @Column("note")
  String note;
}
