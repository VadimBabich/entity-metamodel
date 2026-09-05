package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * The legacy spelling: schema and table arrive as one string, which the mapping context keeps as a
 * single identifier.
 */
@Table("app_schema.v_document_meta")
public class DottedSchemaDocument {

  @Id
  @Column("dm_doc_id")
  public Long id;

  @Column("dm_name")
  public String name;
}
