package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Join fixture: two FK properties toward {@link Account} — the multi-FK-to-same-target shape. */
@Table("memberships")
public class Membership {

  @Id
  @Column("membership_id")
  public Long id;

  @Column("account_id")
  public Long accountId;

  @Column("sponsor_account_id")
  public Long sponsorAccountId;
}
