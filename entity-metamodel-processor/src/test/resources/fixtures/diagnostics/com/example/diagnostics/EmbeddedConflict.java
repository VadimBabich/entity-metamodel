package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;

/**
 * The shortcut form and the annotation it is composed of, on one member. Contradictory to write, but
 * legal — and the only shape in the substrate where an annotation is reachable both directly and as
 * a meta-annotation of a sibling.
 */
@Table("embedded_conflicts")
public class EmbeddedConflict {

  @Id
  @Column("conflict_id")
  Long id;

  @Embedded.Nullable
  @Embedded(onEmpty = Embedded.OnEmpty.USE_EMPTY)
  Address address;
}
