package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Permission-view fixture: the table a listing joins to scope its rows to one principal. Nothing
 * reads these fields in Java — a filter names {@code hasBrowseAccess} as text, the join names the
 * others through refs — so deleting one breaks the resolution under test rather than dead code.
 */
@Table("access_grants")
public class AccessGrant {

  @Id
  @Column("id")
  public Long id;

  @Column("account_id")
  public Long accountId;

  @Column("principal_id")
  public Long principalId;

  @Column("has_browse_access")
  public boolean hasBrowseAccess;
}
