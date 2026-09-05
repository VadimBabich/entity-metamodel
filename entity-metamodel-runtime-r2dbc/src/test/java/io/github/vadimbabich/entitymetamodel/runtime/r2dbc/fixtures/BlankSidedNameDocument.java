package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** A side that is whitespace, not absent — an index check clears it, the substrate does not. */
@Table("app_schema. ")
public class BlankSidedNameDocument {

  @Id
  @Column("dm_doc_id")
  public Long id;
}
