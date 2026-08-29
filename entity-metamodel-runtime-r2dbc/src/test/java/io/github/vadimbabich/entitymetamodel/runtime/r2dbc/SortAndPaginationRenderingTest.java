package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.ACCOUNT;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.MEMBERSHIP;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.accountId;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.ownerEmail;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.owningAccount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.vadimbabich.entitymetamodel.runtime.ExpressionSort;
import io.github.vadimbabich.entitymetamodel.runtime.SqlExpr;
import org.junit.jupiter.api.Test;

/**
 * Sort and pagination, rendered through the dialect's own render context: the bare renderer emits
 * the ANSI {@code FETCH FIRST n ROWS ONLY} form, which MySQL does not accept.
 */
class SortAndPaginationRenderingTest {

  private final QueryRenderer renderer = TestRenderers.postgres();

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
  void anExpressionSortRendersItsFragmentWithTheInstancesColumnFilledIn() {
    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(ACCOUNT)
                .orderBy(ExpressionSort.desc(SqlExpr.raw("length({0})", ownerEmail()))));

    assertThat(statement.sql()).endsWith("ORDER BY length(\"account\".\"owner_email\") DESC");
  }

  @Test
  void anExpressionSortAndATypedSortKeepTheOrderTheyWereGivenIn() {
    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(ACCOUNT)
                .orderBy(
                    ExpressionSort.asc(SqlExpr.raw("length({0})", ownerEmail())),
                    accountId().desc()));

    assertThat(statement.sql())
        .endsWith(
            "ORDER BY length(\"account\".\"owner_email\") ASC, \"account\".\"account_id\" DESC");
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
    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(MEMBERSHIP).join(owningAccount()).orderBy(ownerEmail().asc()));

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
    // "Is there another page" is exists(base.offset(pageEnd)). Dropping the offset turns it into
    // "does anything match", and a paging control then reports a next page forever.
    RenderedStatement statement =
        renderer.renderExistsProbe(
            FluentSelect.from(ACCOUNT).orderBy(ownerEmail().asc()).limit(10).offset(20));

    assertThat(statement.sql()).doesNotContain("ORDER BY");
    assertThat(statement.sql()).endsWith("LIMIT 1 OFFSET 20");
  }

  @Test
  void anExistenceProbeNarrowsTheLimitAndNeverWidensIt() {
    // limit(0) says "no rows", and LIMIT 1 would answer true for a query that selects nothing.
    assertThat(renderer.renderExistsProbe(FluentSelect.from(ACCOUNT).limit(0)).sql())
        .endsWith("LIMIT 0");
    assertThat(renderer.renderExistsProbe(FluentSelect.from(ACCOUNT).limit(5)).sql())
        .endsWith("LIMIT 1");
  }

  @Test
  void aNegativeLimitOrOffsetIsRejectedWhereItIsPassed() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> FluentSelect.from(ACCOUNT).limit(-1));
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> FluentSelect.from(ACCOUNT).offset(-1));
  }
}
