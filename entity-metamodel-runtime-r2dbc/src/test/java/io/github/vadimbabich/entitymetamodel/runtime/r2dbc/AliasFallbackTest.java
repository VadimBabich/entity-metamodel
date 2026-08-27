package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.JoinRef;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.LongColumnRecord;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.NotificationPreferenceSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;

/**
 * PostgreSQL truncates an identifier past 63 bytes silently, and two labels agreeing in their first
 * 63 bytes become one column in the row. So a statement whose labels would overflow renames every
 * table in it — whole-statement, so one description has one rendering.
 */
class AliasFallbackTest {

  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);
  private static final EntityRef<NotificationPreferenceSnapshot> SNAPSHOT =
      EntityRef.of(NotificationPreferenceSnapshot.class);
  private static final EntityRef<LongColumnRecord> LONG_COLUMNS =
      EntityRef.of(LongColumnRecord.class);

  private final QueryRenderer renderer =
      new QueryRenderer(new R2dbcMappingContext(), PostgresDialect.INSTANCE);

  @Test
  void aStatementWhoseLabelsFitKeepsTheInstancesOwnAliases() {
    RenderedStatement statement = renderer.render(FluentSelect.from(ACCOUNT));

    assertThat(statement.sql()).contains("\"accounts\" \"account\"");
    assertThat(statement.aliases().aliasOf(ACCOUNT)).isEqualTo("account");
  }

  @Test
  void aLabelPastTheDialectsLimitRenamesTheTableToAPositionalAlias() {
    RenderedStatement statement = renderer.render(FluentSelect.from(SNAPSHOT));

    assertThat(statement.aliases().aliasOf(SNAPSHOT)).isEqualTo("t1");
    assertThat(statement.sql())
        .contains("\"notification_preference_snapshots\" \"t1\"")
        .contains("AS \"t1__last_updated_by_administrator_account_identifier\"");
  }

  @Test
  void everyTableInTheStatementIsRenamedRatherThanOnlyTheOverflowingOne() {
    JoinRef<NotificationPreferenceSnapshot, Account> owner =
        JoinRef.of(SNAPSHOT.property("id", Long.class), ACCOUNT.property("id", Long.class));

    RenderedStatement statement =
        renderer.render(FluentSelect.from(SNAPSHOT).join(owner).alsoSelect(ACCOUNT));

    assertThat(statement.aliases().aliasOf(SNAPSHOT)).isEqualTo("t1");
    assertThat(statement.aliases().aliasOf(ACCOUNT)).isEqualTo("t2");
    assertThat(statement.sql()).doesNotContain("\"account\"").contains("\"accounts\" \"t2\"");
  }

  @Test
  void twoInstancesOfOneTableStayDistinctUnderTheFallback() {
    EntityRef<NotificationPreferenceSnapshot> previous = SNAPSHOT.as("previous");
    JoinRef<NotificationPreferenceSnapshot, NotificationPreferenceSnapshot> predecessor =
        JoinRef.of(SNAPSHOT.property("id", Long.class), SNAPSHOT.property("id", Long.class));

    RenderedStatement statement =
        renderer.render(FluentSelect.from(SNAPSHOT).join(predecessor, previous));

    assertThat(statement.aliases().aliasOf(SNAPSHOT)).isEqualTo("t1");
    assertThat(statement.aliases().aliasOf(previous)).isEqualTo("t2");
  }

  @Test
  void aLabelThatOverflowsEvenUnderPositionalAliasesIsRefusedRatherThanTruncated() {
    // The fallback is not a guarantee: a two-byte alias plus the separator leaves 59 bytes for the
    // column. Refused loudly, because the alternative is an entity hydrating with those columns
    // null while the row carried values.
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> renderer.render(FluentSelect.from(EntityRef.of(LongColumnRecord.class))))
        .withMessageContaining("identifier limit")
        .withMessageContaining("LongColumnRecord");
  }

  @Test
  void anInstanceJoinedOnlyToFilterIsNeverMeasuredForLabelsItDoesNotEmit() {
    // Joining a wide table purely to filter is ordinary, and none of its columns are projected, so
    // nothing of its can truncate. The overflow still drives the statement to positional aliases.
    PropertyRef<Account, Long> accountId = ACCOUNT.property("id", Long.class);
    PropertyRef<LongColumnRecord, Long> recordId = LONG_COLUMNS.property("id", Long.class);

    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(ACCOUNT).join(LONG_COLUMNS).on(accountId.eq(recordId)));

    assertThat(statement.aliases().aliasOf(ACCOUNT)).isEqualTo("t1");
    assertThat(statement.aliases().aliasOf(LONG_COLUMNS)).isEqualTo("t2");
    assertThat(statement.sql()).doesNotContain("t2__");
  }

  @Test
  void theSameInstanceIsRefusedOnceItIsAlsoProjected() {
    PropertyRef<Account, Long> accountId = ACCOUNT.property("id", Long.class);
    PropertyRef<LongColumnRecord, Long> recordId = LONG_COLUMNS.property("id", Long.class);

    FluentSelect<Account> projected =
        FluentSelect.from(ACCOUNT)
            .join(LONG_COLUMNS)
            .on(accountId.eq(recordId))
            .alsoSelect(LONG_COLUMNS);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> renderer.render(projected))
        .withMessageContaining("LongColumnRecord.first");
  }

  @Test
  void aCountOfALongColumnedDescriptionRendersBecauseItProjectsNoLabel() {
    // The asymmetry an embedded-bearing entity already has: counting and probing hydrate nothing.
    FluentSelect<LongColumnRecord> records =
        FluentSelect.from(EntityRef.of(LongColumnRecord.class));

    assertThat(renderer.renderCount(records).sql()).contains("\"long_column_records\" \"t1\"");
    assertThat(renderer.renderExistsProbe(records).sql())
        .contains("\"long_column_records\" \"t1\"");
  }

  @Test
  void theFallbackIsAPureFunctionOfTheDescription() {
    FluentSelect<NotificationPreferenceSnapshot> everySnapshot = FluentSelect.from(SNAPSHOT);

    assertThat(renderer.render(everySnapshot).sql())
        .isEqualTo(renderer.render(everySnapshot).sql());
  }

  @Test
  void aCountOfAnOverflowingDescriptionUsesTheSameAliasesAsItsPage() {
    FluentSelect<NotificationPreferenceSnapshot> everySnapshot = FluentSelect.from(SNAPSHOT);

    assertThat(renderer.renderCount(everySnapshot).sql())
        .contains("\"notification_preference_snapshots\" \"t1\"");
    assertThat(renderer.renderExistsProbe(everySnapshot).sql())
        .contains("\"notification_preference_snapshots\" \"t1\"");
  }

  @Test
  void aRenamedInstanceIsStillReadBackByTheRefTheCallerHolds() {
    RenderedStatement statement = renderer.render(FluentSelect.from(SNAPSHOT));

    assertThat(statement.aliases().projectedLabelPrefix(SNAPSHOT)).isEqualTo("t1__");
  }
}
