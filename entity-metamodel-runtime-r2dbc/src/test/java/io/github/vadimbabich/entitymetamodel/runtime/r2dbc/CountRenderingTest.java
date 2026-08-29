package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.ACCOUNT;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.MEMBERSHIP;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.ownerEmail;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.owningAccount;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Membership;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A page needs its total, and the total is only trustworthy if it counts exactly the rows the page
 * was drawn from — so the count rebuilds the same FROM/JOIN/WHERE and must bind the same values.
 *
 * <p>Re-mapping an already-mapped condition would allocate fresh markers; holding conditions as
 * pure data removes the choice, and {@link #aCountBindsExactlyWhatThePageBinds()} proves it.
 */
class CountRenderingTest {

  private final QueryRenderer renderer = TestRenderers.postgres();

  @Test
  void aCountProjectsATotalInsteadOfColumns() {
    RenderedStatement statement = renderer.renderCount(FluentSelect.from(ACCOUNT));

    assertThat(statement.sql()).startsWith("SELECT COUNT(1) FROM");
    assertThat(statement.sql()).doesNotContain("account__account_id");
  }

  @Test
  void aCountKeepsTheJoinsAndFiltersOfThePageItMirrors() {
    FluentSelect<Membership> page =
        FluentSelect.from(MEMBERSHIP)
            .join(owningAccount())
            .where(ownerEmail().is("owner@example.com"));

    RenderedStatement statement = renderer.renderCount(page);

    assertThat(statement.sql())
        .contains(
            "JOIN \"accounts\" \"account\""
                + " ON \"membership\".\"account_id\" = \"account\".\"account_id\"")
        .endsWith("WHERE \"account\".\"owner_email\" = $1");
  }

  @Test
  void aCountBindsExactlyWhatThePageBinds() {
    FluentSelect<Account> page =
        FluentSelect.from(ACCOUNT)
            .where(
                ownerEmail()
                    .is("owner@example.com")
                    .and(ACCOUNT.property("id", Long.class).in(List.of(1L, 2L))));

    RenderedStatement pageStatement = renderer.render(page);
    RenderedStatement countStatement = renderer.renderCount(page);

    assertThat(countStatement.bindings()).isEqualTo(pageStatement.bindings());
  }

  @Test
  void aCountIgnoresSortAndPagingBecauseItReportsTheTotal() {
    FluentSelect<Account> page =
        FluentSelect.from(ACCOUNT).orderBy(ownerEmail().asc()).limit(10).offset(20);

    RenderedStatement statement = renderer.renderCount(page);

    assertThat(statement.sql()).doesNotContain("ORDER BY").doesNotContain("LIMIT");
  }
}
