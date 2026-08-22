package com.example.diagnostics;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Refers to an entity whose @Table arrives through a stereotype. */
@Table("composed_ref_holders")
public class ComposedRefHolder {

  @Id
  @Column("holder_id")
  Long id;

  List<ComposedChild> children;
}
