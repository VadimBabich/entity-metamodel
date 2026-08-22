package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** A constraint on a primitive field — ordinary code with Bean Validation on the classpath. */
@Table("validated_entities")
public class ValidatedEntity {

  @Id
  @Column("row_id")
  Long id;

  @Positive
  @Column("amount")
  int amount;
}
