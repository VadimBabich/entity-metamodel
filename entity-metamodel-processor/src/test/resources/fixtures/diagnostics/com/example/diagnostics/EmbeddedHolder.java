package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;

/**
 * The embedded member arrives through Spring's shortcut form, so the annotation on the field is
 * {@code @Embedded.Nullable} and being embedded is something only its meta-annotation says.
 */
@Table("embedded_holders")
public class EmbeddedHolder {

  @Id
  @Column("holder_id")
  Long id;

  @Embedded.Nullable
  Address address;

  @Column("label")
  String label;
}
