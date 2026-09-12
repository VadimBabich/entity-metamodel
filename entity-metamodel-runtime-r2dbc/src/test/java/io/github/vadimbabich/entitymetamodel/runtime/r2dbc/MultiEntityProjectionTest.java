package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.ACCOUNT;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.MEMBERSHIP;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.SPONSOR;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.owningAccount;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.sponsoringAccount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

/**
 * Joining a table and reading it back are separate choices: most joins exist to filter, and
 * projecting their columns would pay for data nobody reads. An instance is projected only when it
 * is asked for.
 */
class MultiEntityProjectionTest {

  private final QueryRenderer renderer = TestRenderers.postgres();

  @Test
  void aJoinedInstanceIsNotProjectedUnlessItIsAskedFor() {
    RenderedStatement statement =
        renderer.render(FluentSelect.from(MEMBERSHIP).join(owningAccount()));

    assertThat(statement.sql()).doesNotContain("account__");
  }

  @Test
  void anAskedForInstanceContributesItsColumnsUnderItsOwnLabels() {
    RenderedStatement statement =
        renderer.render(FluentSelect.from(MEMBERSHIP).join(owningAccount()).alsoSelect(ACCOUNT));

    assertThat(statement.sql())
        .contains("\"membership\".\"membership_id\" AS \"membership__membership_id\"")
        .contains("\"account\".\"owner_email\" AS \"account__owner_email\"");
  }

  @Test
  void twoInstancesOfOneTableProjectDistinctLabels() {
    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(MEMBERSHIP)
                .join(owningAccount())
                .join(sponsoringAccount(), SPONSOR)
                .alsoSelect(ACCOUNT)
                .alsoSelect(SPONSOR));

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
    // A paged endpoint asks for the total and the page from one description, so if only one
    // terminal validates the other fails later and elsewhere.
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> renderer.renderCount(FluentSelect.from(MEMBERSHIP).alsoSelect(ACCOUNT)))
        .withMessageContaining("not part of this statement");
  }

  @Test
  void askingForTheSameInstanceTwiceDoesNotDuplicateItsColumns() {
    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(MEMBERSHIP)
                .join(owningAccount())
                .alsoSelect(ACCOUNT)
                .alsoSelect(ACCOUNT));

    assertThat(statement.sql().split("account__owner_email", -1)).hasSize(2);
  }
}
