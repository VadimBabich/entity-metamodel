package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Maps to a table that is deliberately never created, so failures have something to fail on. */
@Table("ghosts")
public class Ghost {

  @Id
  Long id;
}
