package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Rendering fixture: one {@code @Column}-overridden property and one strategy-resolved property,
 * so a rendered statement shows both name-resolution paths coming from the mapping context.
 */
@Table("accounts")
public class Account {

  @Id
  @Column("account_id")
  public Long id;

  public String ownerEmail;

  public AccountState state;
}
