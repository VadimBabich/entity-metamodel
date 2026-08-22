package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Mapping annotations on the accessors rather than the fields — a minority style, but one the
 * mapping context reads: it merges annotations from the field, the getter and the setter.
 */
@Table("accessor_annotated")
public class AccessorAnnotated {

  @Id
  @Column("row_id")
  Long id;

  String note;

  String renamed;

  @Transient
  public String getNote() {
    return note;
  }

  @Column("renamed_col")
  public String getRenamed() {
    return renamed;
  }
}
