package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.SqlExpr;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;

/**
 * The escape hatch for what the closed algebra cannot express. It is an owned type by construction,
 * so no substrate expression ever appears in a public signature, and the fragment is a template:
 * placeholders become the dialect's markers and the values travel as binds.
 *
 * <p>{@link #aValueThatLooksLikeSqlIsStillOnlyAValue()} is the reason the door is shaped this way
 * rather than as string concatenation.
 */
class RawSqlDoorTest {

  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);

  private final QueryRenderer renderer =
      new QueryRenderer(new R2dbcMappingContext(), PostgresDialect.INSTANCE);

  @Test
  void aRawFragmentRendersWithItsPlaceholdersReplacedByMarkers() {
    Condition lowercasedEmail = SqlExpr.raw("lower(owner_email) = ?", "owner@example.com");

    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(lowercasedEmail));

    assertThat(statement.sql()).endsWith("WHERE lower(owner_email) = $1");
    assertThat(statement.values()).containsExactly("owner@example.com");
  }

  @Test
  void aValueThatLooksLikeSqlIsStillOnlyAValue() {
    Condition injected = SqlExpr.raw("owner_email = ?", "x' OR 1=1 --");

    RenderedStatement statement = renderer.render(FluentSelect.from(ACCOUNT).where(injected));

    assertThat(statement.sql()).endsWith("WHERE owner_email = $1");
    assertThat(statement.sql()).doesNotContain("OR 1=1");
    assertThat(statement.values()).containsExactly("x' OR 1=1 --");
  }

  @Test
  void severalPlaceholdersTakeMarkersInTheOrderTheyAppear() {
    Condition between = SqlExpr.raw("account_id BETWEEN ? AND ?", 10L, 20L);

    RenderedStatement statement = renderer.render(FluentSelect.from(ACCOUNT).where(between));

    assertThat(statement.sql()).endsWith("WHERE account_id BETWEEN $1 AND $2");
    assertThat(statement.values()).containsExactly(10L, 20L);
  }

  @Test
  void aRawFragmentWithoutValuesRendersUnchanged() {
    Condition notArchived = SqlExpr.raw("owner_email IS NOT NULL");

    RenderedStatement statement = renderer.render(FluentSelect.from(ACCOUNT).where(notArchived));

    assertThat(statement.sql()).endsWith("WHERE owner_email IS NOT NULL");
    assertThat(statement.values()).isEmpty();
  }

  @Test
  void aRawFragmentComposedIntoAJunctionIsGroupedLikeAnyOtherOperand() {
    Condition typed = ACCOUNT.property("id", Long.class).gt(0L);
    Condition rawEither =
        SqlExpr.raw("owner_email = ?", "first@example.com")
            .or(SqlExpr.raw("owner_email = ?", "second@example.com"));

    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(typed.and(rawEither)));

    assertThat(statement.sql())
        .endsWith(
            "WHERE (\"account\".\"account_id\" > $1)"
                + " AND ((owner_email = $2) OR (owner_email = $3))");
  }
}
