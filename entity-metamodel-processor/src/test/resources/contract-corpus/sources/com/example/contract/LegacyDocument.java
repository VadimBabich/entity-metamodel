package com.example.contract;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Inheritance-flattening witness: inherited members interleave alphabetically with own members. */
@Table("legacy_documents")
public class LegacyDocument extends BaseDocument {

  @Id
  @Column("document_id")
  Long id;

  @Column("title")
  String title;
}
