package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Marks an inherited field transient by overriding its accessor — Spring honours this. */
@Table("accessor_subclasses")
public class AccessorSubclass extends AccessorBase {

  @Id
  @Column("row_id")
  Long id;

  @Override
  @Transient
  public String getInherited() {
    return super.getInherited();
  }
}
