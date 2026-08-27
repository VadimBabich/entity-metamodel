package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;

/**
 * Rendering is a pure function of the description: no connection, no database, no Spring context.
 * Names appear quoted because the statement renders through the dialect's render context.
 *
 * <p>The load-bearing case is
 * {@link #anOrOperandIsParenthesisedSoItCannotEscapeASurroundingAnd()}: an ungrouped OR lets
 * AND-over-OR precedence widen the match silently, so the grouping is pinned by exact SQL.
 */
class QueryRendererTest {

  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);

  private final QueryRenderer renderer =
      new QueryRenderer(new R2dbcMappingContext(), PostgresDialect.INSTANCE);

  private static PropertyRef<Account, Long> id() {
    return ACCOUNT.property("id", Long.class);
  }

  private static PropertyRef<Account, String> ownerEmail() {
    return ACCOUNT.property("ownerEmail", String.class);
  }

  @Test
  void aSelectWithoutAWhereClauseProjectsEveryColumnAgainstTheInstanceAlias() {
    RenderedStatement statement = renderer.render(FluentSelect.from(ACCOUNT));

    assertThat(statement.sql())
        .isEqualTo(
            "SELECT \"account\".\"account_id\" AS \"account__account_id\","
                + " \"account\".\"owner_email\" AS \"account__owner_email\","
                + " \"account\".\"state\" AS \"account__state\""
                + " FROM \"accounts\" \"account\"");
    assertThat(statement.values()).isEmpty();
  }

  @Test
  void aComparisonBindsItsValueRatherThanInliningIt() {
    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(ownerEmail().is("owner@example.com")));

    assertThat(statement.sql()).endsWith("WHERE \"account\".\"owner_email\" = $1");
    assertThat(statement.values()).containsExactly("owner@example.com");
  }

  @Test
  void anInclusionBindsOneMarkerPerValue() {
    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(id().in(List.of(1L, 2L, 3L))));

    assertThat(statement.sql()).endsWith("WHERE \"account\".\"account_id\" IN ($1, $2, $3)");
    assertThat(statement.values()).containsExactly(1L, 2L, 3L);
  }

  @Test
  void aNullCheckBindsNothing() {
    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(ownerEmail().isNull()));

    assertThat(statement.sql()).endsWith("WHERE \"account\".\"owner_email\" IS NULL");
    assertThat(statement.values()).isEmpty();
  }

  @Test
  void markersAreAllocatedInTheOrderTheirValuesAreBound() {
    Condition emailThenId = ownerEmail().is("owner@example.com").and(id().gt(10L));

    RenderedStatement statement = renderer.render(FluentSelect.from(ACCOUNT).where(emailThenId));

    assertThat(statement.sql())
        .endsWith("WHERE (\"account\".\"owner_email\" = $1) AND (\"account\".\"account_id\" > $2)");
    assertThat(statement.values()).containsExactly("owner@example.com", 10L);
  }

  @Test
  void anOrOperandIsParenthesisedSoItCannotEscapeASurroundingAnd() {
    Condition eitherEmail =
        ownerEmail().is("first@example.com").or(ownerEmail().is("second@example.com"));
    Condition activeAndEitherEmail = id().gt(0L).and(eitherEmail);

    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(activeAndEitherEmail));

    assertThat(statement.sql())
        .endsWith(
            "WHERE (\"account\".\"account_id\" > $1)"
                + " AND ((\"account\".\"owner_email\" = $2)"
                + " OR (\"account\".\"owner_email\" = $3))");
  }

  @Test
  void aNegatedConditionIsGroupedSoTheNotCoversAllOfIt() {
    Condition eitherEmail =
        ownerEmail().is("first@example.com").or(ownerEmail().is("second@example.com"));

    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(eitherEmail.not()));

    assertThat(statement.sql())
        .endsWith(
            "WHERE NOT ((\"account\".\"owner_email\" = $1)"
                + " OR (\"account\".\"owner_email\" = $2))");
    assertThat(statement.values()).containsExactly("first@example.com", "second@example.com");
  }

  @Test
  void aNegatedConditionComposedIntoAJunctionKeepsBothGroupings() {
    Condition activeAndNotExcluded = id().gt(0L).and(id().in(List.of(7L, 8L)).not());

    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(activeAndNotExcluded));

    assertThat(statement.sql())
        .endsWith(
            "WHERE (\"account\".\"account_id\" > $1)"
                + " AND (NOT (\"account\".\"account_id\" IN ($2, $3)))");
    assertThat(statement.values()).containsExactly(0L, 7L, 8L);
  }

  @Test
  void aSecondInstanceOfTheSameTableRendersUnderItsOwnAlias() {
    EntityRef<Account> sponsor = ACCOUNT.as("sponsor");

    RenderedStatement statement =
        renderer.render(FluentSelect.from(sponsor).where(id().of(sponsor).is(7L)));

    assertThat(statement.sql())
        .isEqualTo(
            "SELECT \"account_sponsor\".\"account_id\" AS \"account_sponsor__account_id\","
                + " \"account_sponsor\".\"owner_email\" AS \"account_sponsor__owner_email\","
                + " \"account_sponsor\".\"state\" AS \"account_sponsor__state\""
                + " FROM \"accounts\" \"account_sponsor\""
                + " WHERE \"account_sponsor\".\"account_id\" = $1");
  }

  @Test
  void renderingTheSameDescriptionTwiceProducesTheSameStatement() {
    FluentSelect<Account> select =
        FluentSelect.from(ACCOUNT).where(ownerEmail().is("owner@example.com"));

    RenderedStatement first = renderer.render(select);
    RenderedStatement second = renderer.render(select);

    assertThat(first.sql()).isEqualTo(second.sql());
    assertThat(first.values()).isEqualTo(second.values());
  }
}
