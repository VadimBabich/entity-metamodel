package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** More dots than a schema and a table can account for. */
@Table("catalog.app_schema.v_document_meta")
public class ThreePartNameDocument {

  @Id
  @Column("dm_doc_id")
  public Long id;
}
