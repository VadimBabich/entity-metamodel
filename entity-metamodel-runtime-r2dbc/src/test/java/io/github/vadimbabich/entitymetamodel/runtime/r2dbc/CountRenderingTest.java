package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.JoinRef;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Membership;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;

/**
 * A page needs its total, and the total is only trustworthy if it counts exactly the rows the page
 * was drawn from — so the count rebuilds the same FROM/JOIN/WHERE and must bind the same values.
 *
 * <p>The spike found that reusing an already-mapped condition preserves bind identity while
 * re-mapping allocates fresh markers, which made "reuse, never re-map" a rule to remember. Holding
 * conditions as pure data removes the choice: there is nothing mapped to reuse or lose, and
 * {@link #aCountBindsExactlyWhatThePageBinds()} is what proves it rather than assuming it.
 */
class CountRenderingTest {

  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);
  private static final EntityRef<Membership> MEMBERSHIP = EntityRef.of(Membership.class);

  private final QueryRenderer renderer =
      new QueryRenderer(new R2dbcMappingContext(), PostgresDialect.INSTANCE);

  private static PropertyRef<Account, String> ownerEmail() {
    return ACCOUNT.property("ownerEmail", String.class);
  }

  private static JoinRef<Membership, Account> account() {
    return JoinRef.of(
        MEMBERSHIP.property("accountId", Long.class), ACCOUNT.property("id", Long.class));
  }

  @Test
  void aCountProjectsATotalInsteadOfColumns() {
    RenderedStatement statement = renderer.renderCount(FluentSelect.from(ACCOUNT));

    assertThat(statement.sql()).startsWith("SELECT COUNT(1) FROM");
    assertThat(statement.sql()).doesNotContain("account__account_id");
  }

  @Test
  void aCountKeepsTheJoinsAndFiltersOfThePageItMirrors() {
    FluentSelect<Membership> page =
        FluentSelect.from(MEMBERSHIP).join(account()).where(ownerEmail().is("owner@example.com"));

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
