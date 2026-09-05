package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** A dot with nothing after it: an empty side the split must not hand to the substrate. */
@Table("trailingdot.")
public class TrailingDotDocument {

  @Id
  @Column("dm_doc_id")
  public Long id;
}
