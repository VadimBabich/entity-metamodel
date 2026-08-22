package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** JavaBeans leaves a name whose first two characters are uppercase alone: getURL() is URL. */
@Table("acronym_accessors")
public class AcronymAccessor {

  @Id
  @Column("row_id")
  Long id;

  String URL;

  @Transient
  public String getURL() {
    return URL;
  }
}
