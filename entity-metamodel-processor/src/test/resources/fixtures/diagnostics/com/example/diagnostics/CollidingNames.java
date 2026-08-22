package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Two property names that transform to one member name — a mixed-convention legacy schema. */
@Table("colliding_names")
public class CollidingNames {

  @Id
  @Column("colliding_id")
  Long id;

  @Column("source_url")
  String source_url;

  @Column("source_url_2")
  String sourceUrl;
}
