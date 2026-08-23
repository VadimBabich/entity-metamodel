package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.JoinRef;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Membership;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;

/**
 * Joining a table and reading it back are separate choices: most joins exist to filter, and
 * projecting their columns would pay for data nobody reads. An instance is projected only when it
 * is asked for.
 */
class MultiEntityProjectionTest {

  private static final EntityRef<Membership> MEMBERSHIP = EntityRef.of(Membership.class);
  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);

  private final QueryRenderer renderer =
      new QueryRenderer(new R2dbcMappingContext(), PostgresDialect.INSTANCE);

  private static JoinRef<Membership, Account> account() {
    return JoinRef.of(
        MEMBERSHIP.property("accountId", Long.class), ACCOUNT.property("id", Long.class));
  }

  private static JoinRef<Membership, Account> sponsorAccount() {
    return JoinRef.of(
        MEMBERSHIP.property("sponsorAccountId", Long.class), ACCOUNT.property("id", Long.class));
  }

  @Test
  void aJoinedInstanceIsNotProjectedUnlessItIsAskedFor() {
    RenderedStatement statement =
        renderer.render(FluentSelect.from(MEMBERSHIP).join(account()));

    assertThat(statement.sql()).doesNotContain("account__");
  }

  @Test
  void anAskedForInstanceContributesItsColumnsUnderItsOwnLabels() {
    RenderedStatement statement =
        renderer.render(FluentSelect.from(MEMBERSHIP).join(account()).alsoSelect(ACCOUNT));

    assertThat(statement.sql())
        .contains("\"membership\".\"membership_id\" AS \"membership__membership_id\"")
        .contains("\"account\".\"owner_email\" AS \"account__owner_email\"");
  }

  @Test
  void twoInstancesOfOneTableProjectDistinctLabels() {
    EntityRef<Account> sponsor = ACCOUNT.as("sponsor");

    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(MEMBERSHIP)
                .join(account())
                .join(sponsorAccount(), sponsor)
                .alsoSelect(ACCOUNT)
                .alsoSelect(sponsor));

    assertThat(statement.sql())
        .contains("\"account\".\"account_id\" AS \"account__account_id\"")
        .contains("\"account_sponsor\".\"account_id\" AS \"account_sponsor__account_id\"");
  }

  @Test
  void askingForAnInstanceTheStatementNeverNamedIsRejected() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> renderer.render(FluentSelect.from(MEMBERSHIP).alsoSelect(ACCOUNT)))
        .withMessageContaining("not part of this statement");
  }

  @Test
  void aCountRejectsEveryDescriptionThePageRejects() {
    // A paged endpoint asks for the total and the page from one description. If only one terminal
    // validates, the other fails later and elsewhere.
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> renderer.renderCount(FluentSelect.from(MEMBERSHIP).alsoSelect(ACCOUNT)))
        .withMessageContaining("not part of this statement");
  }

  @Test
  void askingForTheSameInstanceTwiceDoesNotDuplicateItsColumns() {
    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(MEMBERSHIP)
                .join(account())
                .alsoSelect(ACCOUNT)
                .alsoSelect(ACCOUNT));

    assertThat(statement.sql().split("account__owner_email", -1)).hasSize(2);
  }
}
