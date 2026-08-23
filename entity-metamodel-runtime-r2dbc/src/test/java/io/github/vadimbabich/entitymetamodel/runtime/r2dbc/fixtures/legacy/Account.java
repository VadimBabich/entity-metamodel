package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.legacy;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Shares its simple name with the other fixture {@code Account} in a different package, which is
 * how two distinct entity types arrive at one default alias.
 */
@Table("legacy_accounts")
public class Account {

  @Id
  public Long id;
}
