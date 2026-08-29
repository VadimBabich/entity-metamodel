package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.ACCOUNT;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.memberships;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.ownerEmail;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import org.junit.jupiter.api.Test;

/**
 * A distinct description means "the distinct projected rows", and every statement derived from it
 * must mean the same thing. The total and the probe are where that meaning is easiest to lose: a
 * plain {@code COUNT(1)} counts joined rows, and a probe over a literal collapses to one row before
 * its offset applies.
 */
class DistinctRenderingTest {

  private final QueryRenderer renderer = TestRenderers.postgres();

  @Test
  void aDistinctDescriptionRendersSelectDistinct() {
    RenderedStatement statement = renderer.render(FluentSelect.from(ACCOUNT).distinct());

    assertThat(statement.sql())
        .startsWith("SELECT DISTINCT \"account\".\"account_id\" AS \"account__account_id\"");
  }

  @Test
  void theTotalOfADistinctDescriptionCountsItsDistinctRows() {
    FluentSelect<Account> distinctAccounts =
        FluentSelect.from(ACCOUNT).join(memberships()).distinct();

    RenderedStatement total = renderer.renderCount(distinctAccounts);

    // The distinct selection itself is counted, as a derived table: no dialect-portable COUNT
    // expression says "distinct over these columns" once the projection has more than one.
    assertThat(total.sql())
        .startsWith("SELECT COUNT(1) FROM (SELECT DISTINCT ")
        .contains(
            "JOIN \"memberships\" \"membership\""
                + " ON \"account\".\"account_id\" = \"membership\".\"account_id\"")
        .endsWith(") \"distinct_rows\"");
  }

  @Test
  void aDistinctTotalBindsExactlyWhatItsPageBinds() {
    FluentSelect<Account> page =
        FluentSelect.from(ACCOUNT)
            .join(memberships())
            .distinct()
            .where(ownerEmail().is("owner@example.com"));

    RenderedStatement pageStatement = renderer.render(page);
    RenderedStatement totalStatement = renderer.renderCount(page);

    assertThat(totalStatement.bindings()).isEqualTo(pageStatement.bindings());
  }

  @Test
  void aDistinctTotalStripsSortAndPagingLikeAnyTotal() {
    FluentSelect<Account> page =
        FluentSelect.from(ACCOUNT).distinct().orderBy(ownerEmail().asc()).limit(10).offset(20);

    RenderedStatement total = renderer.renderCount(page);

    assertThat(total.sql()).doesNotContain("ORDER BY").doesNotContain("LIMIT");
  }

  @Test
  void aDistinctProbeKeepsTheProjectionSoItsOffsetSkipsDistinctRows() {
    FluentSelect<Account> pastTheSecondRow =
        FluentSelect.from(ACCOUNT).join(memberships()).distinct().offset(2);

    RenderedStatement probe = renderer.renderExistsProbe(pastTheSecondRow);

    // A probe over a literal is one row before DISTINCT even looks at it, and an offset then
    // always skips it — the probe would answer "no more pages" while distinct rows remain.
    assertThat(probe.sql())
        .startsWith("SELECT DISTINCT \"account\".\"account_id\"")
        .endsWith("LIMIT 1 OFFSET 2");
  }

  @Test
  void aProbeWithoutDistinctStillProjectsALiteral() {
    RenderedStatement probe = renderer.renderExistsProbe(FluentSelect.from(ACCOUNT));

    assertThat(probe.sql()).startsWith("SELECT 1 FROM");
  }
}
