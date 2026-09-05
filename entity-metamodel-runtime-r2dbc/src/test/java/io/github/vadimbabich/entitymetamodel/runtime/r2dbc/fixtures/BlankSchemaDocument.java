package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** A schema attribute that is present but blank, which the mapping context treats as absent. */
@Table(value = "app_schema.v_document_meta", schema = "   ")
public class BlankSchemaDocument {

  @Id
  @Column("dm_doc_id")
  public Long id;
}
