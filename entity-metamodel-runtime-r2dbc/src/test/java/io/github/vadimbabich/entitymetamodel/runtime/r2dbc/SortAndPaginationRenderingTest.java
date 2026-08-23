package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.JoinRef;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Membership;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;

/**
 * Sort and pagination close the bounded-page shape production actually asks for.
 *
 * <p>The limit clause is rendered through the dialect's own render context rather than the bare
 * renderer: the bare one emits the ANSI {@code FETCH FIRST n ROWS ONLY} form, which MySQL does not
 * accept, so a renderer holding a dialect and ignoring it would quietly emit SQL that fails on a
 * supported target.
 */
class SortAndPaginationRenderingTest {

  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);
  private static final EntityRef<Membership> MEMBERSHIP = EntityRef.of(Membership.class);

  private final QueryRenderer renderer =
      new QueryRenderer(new R2dbcMappingContext(), PostgresDialect.INSTANCE);

  private static PropertyRef<Account, String> ownerEmail() {
    return ACCOUNT.property("ownerEmail", String.class);
  }

  private static PropertyRef<Account, Long> accountId() {
    return ACCOUNT.property("id", Long.class);
  }

  @Test
  void ascendingAndDescendingBothStateTheirDirection() {
    RenderedStatement ascending =
        renderer.render(FluentSelect.from(ACCOUNT).orderBy(ownerEmail().asc()));
    RenderedStatement descending =
        renderer.render(FluentSelect.from(ACCOUNT).orderBy(ownerEmail().desc()));

    assertThat(ascending.sql()).endsWith("ORDER BY \"account\".\"owner_email\" ASC");
    assertThat(descending.sql()).endsWith("ORDER BY \"account\".\"owner_email\" DESC");
  }

  @Test
  void severalSortsKeepTheOrderTheyWereGivenIn() {
    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(ACCOUNT).orderBy(ownerEmail().asc(), accountId().desc()));

    assertThat(statement.sql())
        .endsWith("ORDER BY \"account\".\"owner_email\" ASC, \"account\".\"account_id\" DESC");
  }

  @Test
  void aLimitAndOffsetRenderInTheDialectsOwnForm() {
    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).limit(50).offset(100));

    assertThat(statement.sql()).endsWith("LIMIT 50 OFFSET 100");
  }

  @Test
  void aLimitWithoutAnOffsetRendersAlone() {
    RenderedStatement statement = renderer.render(FluentSelect.from(ACCOUNT).limit(10));

    assertThat(statement.sql()).endsWith("LIMIT 10");
  }

  @Test
  void filterSortAndPageRenderInSqlOrderRegardlessOfBuilderOrder() {
    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(ACCOUNT)
                .limit(25)
                .offset(50)
                .where(ownerEmail().is("owner@example.com"))
                .orderBy(accountId().asc()));

    assertThat(statement.sql())
        .containsSubsequence(
            "FROM",
            "WHERE \"account\".\"owner_email\" = $1",
            "ORDER BY \"account\".\"account_id\" ASC",
            "LIMIT 25");
    assertThat(statement.values()).containsExactly("owner@example.com");
  }

  @Test
  void sortingOnAJoinedInstanceUsesThatInstancesTable() {
    JoinRef<Membership, Account> account =
        JoinRef.of(
            MEMBERSHIP.property("accountId", Long.class), ACCOUNT.property("id", Long.class));

    RenderedStatement statement =
        renderer.render(FluentSelect.from(MEMBERSHIP).join(account).orderBy(ownerEmail().asc()));

    assertThat(statement.sql()).endsWith("ORDER BY \"account\".\"owner_email\" ASC");
  }

  @Test
  void sortingOnAnInstanceThatIsNotInTheStatementIsRejected() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () -> renderer.render(FluentSelect.from(MEMBERSHIP).orderBy(ownerEmail().asc())))
        .withMessageContaining("not part of this statement");
  }

  @Test
  void anExistenceProbeKeepsTheOffsetButDropsTheSort() {
    // "Is there another page" is asked as exists(base.offset(pageEnd)). Dropping the offset turns
    // it into "does anything match", so a paging control reports a next page forever. The sort
    // genuinely does not matter: how many rows follow the first N does not depend on their order.
    RenderedStatement statement =
        renderer.renderExistsProbe(
            FluentSelect.from(ACCOUNT).orderBy(ownerEmail().asc()).limit(10).offset(20));

    assertThat(statement.sql()).doesNotContain("ORDER BY");
    assertThat(statement.sql()).endsWith("LIMIT 1 OFFSET 20");
  }

  @Test
  void aNegativeLimitOrOffsetIsRejectedWhereItIsPassed() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> FluentSelect.from(ACCOUNT).limit(-1));
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> FluentSelect.from(ACCOUNT).offset(-1));
  }
}
