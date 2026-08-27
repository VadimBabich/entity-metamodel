package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Identifier-limit fixture. Its default alias is 30 bytes, so with the {@code __} separator the
 * longest column below projects a 80-byte label — past PostgreSQL's 63, which truncates silently.
 * Both names are the length real ones reach, not padding.
 */
@Table("notification_preference_snapshots")
public class NotificationPreferenceSnapshot {

  @Id
  @Column("id")
  public Long id;

  @Column("last_updated_by_administrator_account_identifier")
  public String lastUpdatedBy;
}
