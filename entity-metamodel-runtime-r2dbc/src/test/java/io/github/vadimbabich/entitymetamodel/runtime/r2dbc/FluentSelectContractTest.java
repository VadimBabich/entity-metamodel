package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;

/**
 * A builder step returns a new description and leaves the receiver alone, so one half-built query
 * can be finished two different ways concurrently. Immutability is asserted through what the
 * descriptions render, not through reference identity — a copy that shared mutable state would
 * satisfy {@code isNotSameAs} and still corrupt its sibling.
 */
class FluentSelectContractTest {

  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);

  private final QueryRenderer renderer =
      new QueryRenderer(new R2dbcMappingContext(), PostgresDialect.INSTANCE);

  @Test
  void addingAWhereClauseLeavesTheOriginalDescriptionUnfiltered() {
    FluentSelect<Account> everyAccount = FluentSelect.from(ACCOUNT);

    FluentSelect<Account> oneAccount =
        everyAccount.where(ACCOUNT.property("id", Long.class).is(1L));

    assertThat(renderer.render(everyAccount).sql()).doesNotContain("WHERE");
    assertThat(renderer.render(oneAccount).sql()).contains("WHERE");
  }

  @Test
  void twoQueriesDerivedFromOneBaseDoNotSeeEachOthersFilters() {
    PropertyRef<Account, Long> id = ACCOUNT.property("id", Long.class);
    FluentSelect<Account> everyAccount = FluentSelect.from(ACCOUNT);

    FluentSelect<Account> firstAccount = everyAccount.where(id.is(1L));
    FluentSelect<Account> secondAccount = everyAccount.where(id.is(2L));

    assertThat(renderer.render(firstAccount).values()).containsExactly(1L);
    assertThat(renderer.render(secondAccount).values()).containsExactly(2L);
  }

  @Test
  void aSecondFilterNarrowsInsteadOfReplacingTheFirst() {
    PropertyRef<Account, Long> id = ACCOUNT.property("id", Long.class);
    PropertyRef<Account, String> ownerEmail = ACCOUNT.property("ownerEmail", String.class);

    // Conditional accumulation is how these queries get built. Replacing would silently drop an
    // earlier scope — a tenant or owner predicate — and widen the result with no error.
    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(ACCOUNT).where(id.is(1L)).where(ownerEmail.is("owner@example.com")));

    assertThat(statement.sql())
        .endsWith("WHERE (\"account\".\"account_id\" = $1) AND (\"account\".\"owner_email\" = $2)");
    assertThat(statement.values()).containsExactly(1L, "owner@example.com");
  }

  @Test
  void aMissingEntityOrConditionIsRejectedWhereItIsPassed() {
    assertThatNullPointerException().isThrownBy(() -> FluentSelect.from(null));
    assertThatNullPointerException().isThrownBy(() -> FluentSelect.from(ACCOUNT).where(null));
  }
}
