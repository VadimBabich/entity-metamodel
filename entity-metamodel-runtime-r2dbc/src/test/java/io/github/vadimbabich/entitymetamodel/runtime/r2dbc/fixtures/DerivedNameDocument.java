package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;

/**
 * Deliberately unannotated: its name comes from the naming strategy, the only source of an
 * identifier that standardizes its own letter case.
 */
public class DerivedNameDocument {

  @Id
  @Column("dm_doc_id")
  public Long id;
}
