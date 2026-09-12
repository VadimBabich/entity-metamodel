package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("private_nested_types")
public class PrivateNestedType {

  @Id
  @Column("id")
  Long id;

  @Column("status")
  Status status;

  private enum Status {
    OPEN,
    CLOSED
  }
}
