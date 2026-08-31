package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.ACCOUNT;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.accountId;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.ownerEmail;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import org.junit.jupiter.api.Test;

/**
 * The query recipes the README publishes, rendered through the same renderer a caller would use.
 * A recipe stands in place of a feature here, and a published example nothing exercises rots first.
 */
class DocumentedRecipeRenderingTest {

  private static final String CURSOR_EMAIL = "z@example.com";
  private static final long CURSOR_ID = 42L;

  private final QueryRenderer renderer = TestRenderers.postgres();

  @Test
  void theExpandedKeysetPredicateParenthesisesInEveryCompositionPosition() {
    Condition after = expandedKeysetAfter();

    RenderedStatement standalone =
        renderer.render(FluentSelect.from(ACCOUNT).where(after));
    RenderedStatement filterThenKeyset =
        renderer.render(
            FluentSelect.from(ACCOUNT).where(ownerEmail().like("%@example.com").and(after)));
    RenderedStatement keysetThenFilter =
        renderer.render(
            FluentSelect.from(ACCOUNT).where(after.and(ownerEmail().like("%@example.com"))));

    assertThat(standalone.sql())
        .endsWith(
            "WHERE (\"account\".\"owner_email\" > $1)"
                + " OR ((\"account\".\"owner_email\" = $2)"
                + " AND (\"account\".\"account_id\" > $3))");
    assertThat(filterThenKeyset.sql())
        .endsWith(
            "WHERE (\"account\".\"owner_email\" LIKE $1)"
                + " AND ((\"account\".\"owner_email\" > $2)"
                + " OR ((\"account\".\"owner_email\" = $3)"
                + " AND (\"account\".\"account_id\" > $4)))");
    assertThat(keysetThenFilter.sql())
        .endsWith(
            "WHERE ((\"account\".\"owner_email\" > $1)"
                + " OR ((\"account\".\"owner_email\" = $2)"
                + " AND (\"account\".\"account_id\" > $3)))"
                + " AND (\"account\".\"owner_email\" LIKE $4)");
  }

  @Test
  void theRowValueKeysetPredicateNamesItsColumnsFromTheMetamodelAndBindsItsValues() {
    // The form a composite index can use, and the reason no new Condition variant is needed for it:
    // the raw door writes the qualified column names and binds everything else.
    Condition after = rowValueKeysetAfter();

    RenderedStatement statement = renderer.render(FluentSelect.from(ACCOUNT).where(after));

    assertThat(statement.sql())
        .endsWith("WHERE (\"account\".\"owner_email\", \"account\".\"account_id\") > ($1, $2)");
    assertThat(statement.values()).containsExactly(CURSOR_EMAIL, CURSOR_ID);
  }

  @Test
  void theRowValueKeysetPredicateParenthesisesOnEitherSideOfACallersFilter() {
    // The README promises both recipes compose "with a filter of your own on either side". The raw
    // door carries no operator precedence of its own, so this is the assertion that promise needs.
    RenderedStatement filterThenKeyset =
        renderer.render(
            FluentSelect.from(ACCOUNT)
                .where(ownerEmail().like("%@example.com").and(rowValueKeysetAfter())));
    RenderedStatement keysetThenFilter =
        renderer.render(
            FluentSelect.from(ACCOUNT)
                .where(rowValueKeysetAfter().and(ownerEmail().like("%@example.com"))));

    assertThat(filterThenKeyset.sql())
        .endsWith(
            "WHERE (\"account\".\"owner_email\" LIKE $1)"
                + " AND ((\"account\".\"owner_email\", \"account\".\"account_id\") > ($2, $3))");
    assertThat(keysetThenFilter.sql())
        .endsWith(
            "WHERE ((\"account\".\"owner_email\", \"account\".\"account_id\") > ($1, $2))"
                + " AND (\"account\".\"owner_email\" LIKE $3)");
  }

  @Test
  void theRowValueKeysetPredicateInvertsWithAnAllDescendingSort() {
    // Published beside the ascending form because the operator is the whole difference, and the
    // wrong one still renders, still seeks, and never advances the traversal.
    Condition before = rowValueKeysetBefore();

    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(ACCOUNT)
                .where(before)
                .orderBy(ownerEmail().desc(), accountId().desc()));

    assertThat(statement.sql())
        .contains("WHERE (\"account\".\"owner_email\", \"account\".\"account_id\") < ($1, $2)")
        .endsWith("ORDER BY \"account\".\"owner_email\" DESC, \"account\".\"account_id\" DESC");
  }

  private static Condition expandedKeysetAfter() {
    return ProductionPatterns.expandedKeysetAfter(
        ownerEmail(), CURSOR_EMAIL, accountId(), CURSOR_ID);
  }

  private static Condition rowValueKeysetAfter() {
    return ProductionPatterns.rowValueKeysetAfter(
        ownerEmail(), CURSOR_EMAIL, accountId(), CURSOR_ID);
  }

  private static Condition rowValueKeysetBefore() {
    return ProductionPatterns.rowValueKeysetBefore(
        ownerEmail(), CURSOR_EMAIL, accountId(), CURSOR_ID);
  }
}
