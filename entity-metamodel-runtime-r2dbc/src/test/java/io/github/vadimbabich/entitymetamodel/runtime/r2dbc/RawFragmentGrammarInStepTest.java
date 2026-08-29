package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.SqlExpr;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The raw door's template grammar is written twice — {@code SqlExpr} validates it, this module's
 * renderer consumes it — because sharing the pattern would guard its text without guarding how
 * each side reads a match. This holds the two in step behaviourally, in both drift directions.
 */
class RawFragmentGrammarInStepTest {

  private static final Pattern UNCONSUMED_REFERENCE = Pattern.compile("\\{[0-9]+}");

  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);

  private final QueryRenderer renderer = TestRenderers.postgres();

  /**
   * A property reference produces a column rather than a marker, which is why the count is of value
   * references rather than of references.
   */
  private static List<AcceptedFragment> grammarMatrix() {
    return List.of(
        new AcceptedFragment(SqlExpr.raw("account_id = {0}", 1L), 1),
        new AcceptedFragment(SqlExpr.raw("{0} = {0}", 1L), 2),
        new AcceptedFragment(SqlExpr.raw("{1} = {0}", 1L, 2L), 2),
        new AcceptedFragment(SqlExpr.raw("{00} = {01}", 1L, 2L), 2),
        new AcceptedFragment(
            SqlExpr.raw("{0} = account_id", ACCOUNT.property("id", Long.class)), 0),
        new AcceptedFragment(
            SqlExpr.raw(
                "{0} = {1}", ACCOUNT.property("id", Long.class),
                ACCOUNT.property("ownerEmail", String.class)),
            0),
        new AcceptedFragment(SqlExpr.raw("owner_email IS NOT NULL"), 0),
        new AcceptedFragment(SqlExpr.raw("payload @> {0}::jsonb", "{}"), 1),

        // Decoys: text a drifted renderer could mistake for a reference. Without these the
        // over-matching direction has nothing to catch.
        new AcceptedFragment(SqlExpr.raw("attributes ? 'archived'"), 0),
        new AcceptedFragment(SqlExpr.raw("attributes ?| {0}", (Object) new String[]{"a"}), 1),
        new AcceptedFragment(SqlExpr.raw("payload @> '{\"state\": 1}' AND id = {0}", 1L), 1),
        new AcceptedFragment(SqlExpr.raw("note = '{ 0 }' AND id = {0}", 1L), 1),
        new AcceptedFragment(SqlExpr.raw("note = '{x}' AND id = {0}", 1L), 1),
        new AcceptedFragment(SqlExpr.raw("note = '{}' AND id = {0}", 1L), 1));
  }

  @Test
  void whatSqlExprAcceptsTheRendererConsumesCompletely() {
    for (AcceptedFragment accepted : grammarMatrix()) {
      RenderedStatement statement =
          renderer.render(FluentSelect.from(ACCOUNT).where(accepted.fragment()));

      // A reference the renderer did not recognise would reach the database as literal braces.
      assertThat(UNCONSUMED_REFERENCE.matcher(renderedFragmentOf(statement)).find())
          .describedAs("unconsumed reference in %s", accepted.fragment().sql())
          .isFalse();
    }
  }

  @Test
  void theRendererBindsExactlyTheValueReferencesSqlExprCounted() {
    for (AcceptedFragment accepted : grammarMatrix()) {
      RenderedStatement statement =
          renderer.render(FluentSelect.from(ACCOUNT).where(accepted.fragment()));

      // Matching more than the validator binds a decoy; matching less leaves a value unbound.
      assertThat(statement.values())
          .describedAs("bindings for %s", accepted.fragment().sql())
          .hasSize(accepted.expectedMarkers());
    }
  }

  private static String renderedFragmentOf(RenderedStatement statement) {
    String sql = statement.sql();

    return sql.substring(sql.indexOf(" WHERE ") + " WHERE ".length());
  }

  private record AcceptedFragment(SqlExpr fragment, int expectedMarkers) {
  }
}
