package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Carries one annotated and one un-annotated persistent field — the strictness flag's witness. */
@Table("loose_entities")
public class LooseEntity {

  @Id
  @Column("loose_id")
  Long id;

  String nickname;
}
