package com.example.diagnostics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Spring counts a @Value-populated property as transient, so it is not a column. */
@Table("spel_populated")
public class SpelPopulated {

  @Id
  @Column("row_id")
  Long id;

  @Value("#{1 + 1}")
  int derived;
}
