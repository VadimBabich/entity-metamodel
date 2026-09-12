package com.example.diagnostics;

import org.springframework.data.relational.core.mapping.Column;

public class HiddenBase {

  @Column("items")
  String items;
}
