package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.SqlExpr;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import org.junit.jupiter.api.Test;

/**
 * The escape hatch for what the closed algebra cannot express.
 * {@link #aValueThatLooksLikeSqlIsStillOnlyAValue()} is why it is a template rather than string
 * concatenation.
 */
class RawSqlDoorTest {

  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);

  private final QueryRenderer renderer = TestRenderers.postgres();

  @Test
  void aRawFragmentRendersWithItsReferencesReplacedByMarkers() {
    Condition lowercasedEmail = SqlExpr.raw("lower(owner_email) = {0}", "owner@example.com");

    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(lowercasedEmail));

    assertThat(statement.sql()).endsWith("WHERE lower(owner_email) = $1");
    assertThat(statement.values()).containsExactly("owner@example.com");
  }

  @Test
  void aValueThatLooksLikeSqlIsStillOnlyAValue() {
    Condition injected = SqlExpr.raw("owner_email = {0}", "x' OR 1=1 --");

    RenderedStatement statement = renderer.render(FluentSelect.from(ACCOUNT).where(injected));

    assertThat(statement.sql()).endsWith("WHERE owner_email = $1");
    assertThat(statement.sql()).doesNotContain("OR 1=1");
    assertThat(statement.values()).containsExactly("x' OR 1=1 --");
  }

  @Test
  void severalReferencesTakeMarkersInTheOrderTheyAppear() {
    Condition between = SqlExpr.raw("account_id BETWEEN {0} AND {1}", 10L, 20L);

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
  void aPropertyArgumentBecomesItsInstancesColumnRatherThanABoundValue() {
    Condition lowercasedMatch =
        SqlExpr.raw(
            "lower({0}) = lower({1})",
            ACCOUNT.property("ownerEmail", String.class),
            "OWNER@example.com");

    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(lowercasedMatch));

    assertThat(statement.sql())
        .endsWith("WHERE lower(\"account\".\"owner_email\") = lower($1)");
    assertThat(statement.values()).containsExactly("OWNER@example.com");
  }

  @Test
  void aFragmentNamingAnInstanceTheStatementNeverCarriesIsRejected() {
    EntityRef<Account> sponsor = ACCOUNT.as("sponsor");
    Condition sponsorEmail =
        SqlExpr.raw("{0} IS NOT NULL", sponsor.property("ownerEmail", String.class));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> renderer.render(FluentSelect.from(ACCOUNT).where(sponsorEmail)))
        .withMessageContaining("not part of this statement");
  }

  @Test
  void oneArgumentReferencedTwiceBindsOnceForEachReference() {
    // A marker per reference, not per argument: markers are positional on some dialects, so
    // reusing one for a second reference would bind the wrong parameter there.
    Condition betweenItself =
        SqlExpr.raw("{0} >= valid_from AND {0} <= valid_to", "2026-08-29");

    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(betweenItself));

    assertThat(statement.sql()).endsWith("WHERE $1 >= valid_from AND $2 <= valid_to");
    assertThat(statement.values()).containsExactly("2026-08-29", "2026-08-29");
  }

  @Test
  void referencesMayNameArgumentsOutOfTheOrderTheyWereGivenIn() {
    Condition reversed =
        SqlExpr.raw("{1} = owner_email AND {0} = account_id", 7L, "owner@example.com");

    RenderedStatement statement = renderer.render(FluentSelect.from(ACCOUNT).where(reversed));

    // Markers are allocated in the order the text reads, so values follow the text rather than the
    // argument list — which is what keeps marker n paired with binding n.
    assertThat(statement.sql()).endsWith("WHERE $1 = owner_email AND $2 = account_id");
    assertThat(statement.values()).containsExactly("owner@example.com", 7L);
  }

  /**
   * PostgreSQL spells jsonb existence with question marks, so each one has to survive into the
   * statement as text even where a real reference stands beside it.
   */
  @Test
  void aQuestionMarkOperatorRendersLiterallyBesideARealReference() {
    // Cast, or the array spreads into the varargs rather than arriving as one argument — see
    // anArrayArgumentNeedsACastOrItSpreadsIntoTheVarargs().
    Condition anyKeyPresent =
        SqlExpr.raw("attributes ?| {0}", (Object) new String[]{"archived"});

    RenderedStatement statement = renderer.render(FluentSelect.from(ACCOUNT).where(anyKeyPresent));

    assertThat(statement.sql()).endsWith("WHERE attributes ?| $1");
  }

  /**
   * A trap inherited from {@code Object...} rather than from the grammar, pinned because the
   * operators that motivated numbered references — jsonb {@code ?|}, {@code ?&} — are exactly the
   * ones whose right-hand side is an array. Both ways of getting it wrong are loud.
   */
  @Test
  void anArrayArgumentNeedsACastOrItSpreadsIntoTheVarargs() {
    String[] twoKeys = {"archived", "draft"};

    // An uncast array becomes the varargs array itself, so the two keys arrive as two arguments and
    // the fragment names only the first. The variable looks redundant and is not: inlining it
    // passes a String[] where Object[] is expected, which -Werror turns into a build failure.
    Object[] spreadByTheCompiler = twoKeys;

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> SqlExpr.raw("attributes ?| {0}", spreadByTheCompiler))
        .withMessageContaining("never references argument 1");

    SqlExpr passedWhole = SqlExpr.raw("attributes ?| {0}", (Object) twoKeys);

    // The cast is needed here too, for the same reason and against a different varargs method.
    assertThat(passedWhole.arguments()).containsExactly((Object) twoKeys);
  }

  @Test
  void bracesThatAreNotAReferenceSurviveIntoTheStatement() {
    Condition jsonContainment =
        SqlExpr.raw("payload @> '{\"state\": 1}' AND account_id = {0}", 7L);

    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(jsonContainment));

    assertThat(statement.sql()).endsWith("WHERE payload @> '{\"state\": 1}' AND account_id = $1");
    assertThat(statement.values()).containsExactly(7L);
  }

  @Test
  void aReferenceCanBeCastImmediatelyAfterItsClosingBrace() {
    Condition typedContainment = SqlExpr.raw("payload @> {0}::jsonb", "{\"state\": 1}");

    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(typedContainment));

    assertThat(statement.sql()).endsWith("WHERE payload @> $1::jsonb");
    assertThat(statement.values()).containsExactly("{\"state\": 1}");
  }

  /**
   * The shape every "parents that have a child" question should take, and the documented answer to
   * to-many restriction. The correlation column is a property ref, so the library writes the outer
   * alias and the fragment survives an alias reassignment; the inner table is the caller's text.
   */
  @Test
  void aCorrelatedExistsFragmentRestrictsWithoutJoining() {
    Condition hasMembership =
        SqlExpr.raw(
            "EXISTS (SELECT 1 FROM memberships m WHERE m.account_id = {0})",
            ACCOUNT.property("id", Long.class));

    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(hasMembership));

    assertThat(statement.sql())
        .endsWith(
            "WHERE EXISTS (SELECT 1 FROM memberships m"
                + " WHERE m.account_id = \"account\".\"account_id\")")
        .doesNotContain("JOIN");
    assertThat(statement.values()).isEmpty();
  }

  @Test
  void aRawFragmentComposedIntoAJunctionIsGroupedLikeAnyOtherOperand() {
    Condition typed = ACCOUNT.property("id", Long.class).gt(0L);
    Condition rawEither =
        SqlExpr.raw("owner_email = {0}", "first@example.com")
            .or(SqlExpr.raw("owner_email = {0}", "second@example.com"));

    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(typed.and(rawEither)));

    assertThat(statement.sql())
        .endsWith(
            "WHERE (\"account\".\"account_id\" > $1)"
                + " AND ((owner_email = $2) OR (owner_email = $3))");
  }
}
