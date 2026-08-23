package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Two identifier hazards in one fixture: the class name is a SQL reserved word, so its default
 * alias must survive being rendered, and {@code placedBy} is mixed case, so the mapping context's
 * quoting decision must reach the statement instead of being folded away.
 */
@Table("orders")
public class Order {

  @Id
  public Long id;

  @Column("placedBy")
  public String placedBy;
}
