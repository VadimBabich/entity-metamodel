package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Filter fixture: carries the boolean column no other fixture has. Nothing reads {@code
 * hasBrowseAccess} in Java — a filter names it as text — so deleting it breaks the resolution under
 * test rather than removing dead code.
 */
@Table("access_grants")
public class AccessGrant {

  @Id
  @Column("id")
  public Long id;

  @Column("has_browse_access")
  public boolean hasBrowseAccess;
}
