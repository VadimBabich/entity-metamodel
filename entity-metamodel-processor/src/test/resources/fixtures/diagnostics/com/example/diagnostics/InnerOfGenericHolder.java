package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("inner_of_generic_holders")
public class InnerOfGenericHolder {

  @Id
  @Column("id")
  Long id;

  @Column("inner")
  GenericOuter<String>.Inner inner;
}
