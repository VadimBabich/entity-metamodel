package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Self-referencing fixture — an org chart, a category tree, a reply-to thread — where traversing
 * one relationship twice means two hops. Nothing reads {@code managerId} in Java: the tests name it
 * through a {@code JoinRef}, so deleting it breaks that resolution rather than removing dead code.
 */
@Table("employees")
public class Employee {

  @Id
  @Column("id")
  public Long id;

  @Column("manager_id")
  public Long managerId;
}
