package com.example.diagnostics;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("inherited_table_ref_holders")
public class InheritedTableRefHolder {

  @Id
  @Column("holder_id")
  Long id;

  @Column("label")
  String label;

  InheritedTableSubclass sole;

  List<InheritedTableSubclass> many;
}
