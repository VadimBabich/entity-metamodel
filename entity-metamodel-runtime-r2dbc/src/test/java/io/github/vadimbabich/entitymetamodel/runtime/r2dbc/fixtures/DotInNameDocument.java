package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** The escape hatch: a name that really contains a dot, disambiguated by an explicit schema. */
@Table(value = "weird.name", schema = "app_schema")
public class DotInNameDocument {

  @Id
  @Column("dm_doc_id")
  public Long id;
}
