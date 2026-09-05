package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Schema-qualified in the canonical spelling, with the schema as its own attribute. */
@Table(value = "v_document_meta", schema = "app_schema")
public class SchemaDocument {

  @Id
  @Column("dm_doc_id")
  public Long id;

  @Column("dm_name")
  public String name;
}
