package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Not generic itself, but inherits a type-variable-typed member — valid source whose flattened
 * member this contract cannot express.
 */
@Table("boxes")
public class Box extends GenericBase<String> {

  @Id
  @Column("box_id")
  Long id;
}
