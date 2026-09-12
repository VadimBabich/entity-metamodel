package io.github.vadimbabich.entitymetamodel.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.vadimbabich.entitymetamodel.runtime.fixtures.Account;
import org.junit.jupiter.api.Test;

/**
 * The raw door's template grammar: {@code {0}} references an argument by position, and every other
 * character — {@code ?}, {@code '}, a brace that is not a reference — is literal text.
 *
 * <p>Numbered references rather than {@code ?} because a question mark is a jsonb operator in the
 * dialects this library targets, and no parser can tell {@code attributes ? 'key'} from a bind. A
 * door for what the typed vocabulary cannot say must be able to say its own dialect's operators.
 */
class SqlExprTemplateTest {

  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);

  @Test
  void aReferenceNamesItsArgumentByPosition() {
    SqlExpr fragment = SqlExpr.raw("{0} = lower({1})", ACCOUNT.property("ownerEmail", String.class),
        "owner@example.com");

    assertThat(fragment.arguments())
        .containsExactly(ACCOUNT.property("ownerEmail", String.class), "owner@example.com");
  }

  @Test
  void oneArgumentCanBeReferencedMoreThanOnce() {
    SqlExpr betweenItself = SqlExpr.raw("{0} <= valid_to AND {0} >= valid_from", "2026-08-29");

    assertThat(betweenItself.arguments()).containsExactly("2026-08-29");
  }

  @Test
  void aLeadingZeroDoesNotChangeWhichArgumentAReferenceNames() {
    SqlExpr padded = SqlExpr.raw("first = {00} AND second = {01}", "first", "second");

    assertThat(padded.arguments()).containsExactly("first", "second");
  }

  @Test
  void aNullArgumentIsRefusedNamingTheFragmentLevelAlternative() {
    assertThatNullPointerException()
        .isThrownBy(() -> SqlExpr.raw("owner_email = {0}", (Object) null))
        .withMessageContaining("argument 0")
        .withMessageContaining("IS NULL");
  }

  @Test
  void anArrayArgumentIsCopiedSoALaterMutationDoesNotReachTheBinding() {
    String[] keys = {"archived"};

    SqlExpr fragment = SqlExpr.raw("attributes ?| {0}", (Object) keys);
    keys[0] = "draft";

    assertThat((String[]) fragment.arguments().get(0)).containsExactly("archived");
  }

  @Test
  void aReferencePastTheLastArgumentIsRefusedNamingWhatWasWrittenAndHowMany() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> SqlExpr.raw("owner_email = {1}", "only-one"))
        // "references {1}" rather than a bare "{1}": the refusal echoes the fragment, so asserting
        // the reference alone would pass on the echo and prove nothing about the diagnosis.
        .withMessageContaining("references {1}")
        .withMessageContaining("1 argument");
  }

  /**
   * The one collision the grammar leaves: a single-element PostgreSQL array literal is spelled
   * exactly like a reference. It fails loudly either way, but only a message naming
   * {@code ARRAY[...]} tells the caller what to write instead.
   */
  @Test
  void anArrayLiteralIsIndistinguishableFromAReferenceAndIsRefusedWithTheFix() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> SqlExpr.raw("tags && '{5}'::int[]"))
        .withMessageContaining("references {5}")
        .withMessageContaining("ARRAY[");
  }

  @Test
  void aReferenceTooLargeToBeAnIndexIsRefusedAsOutOfRangeRatherThanAsAParseFailure() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> SqlExpr.raw("id = {99999999999999}", 1L))
        .withMessageContaining("references {99999999999999}")
        .withMessageContaining("1 argument");
  }

  @Test
  void anArgumentNoReferenceUsesIsRefusedBecauseItIsACallerMistakeRatherThanSlack() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> SqlExpr.raw("owner_email = {0}", "used", "forgotten"))
        .withMessageContaining("never references argument 1");
  }

  @Test
  void aQuestionMarkIsLiteralSoTheDialectsOwnOperatorsAreWritable() {
    SqlExpr jsonbExistence = SqlExpr.raw("attributes ? 'archived'");

    assertThat(jsonbExistence.arguments()).isEmpty();
    assertThat(jsonbExistence.sql()).isEqualTo("attributes ? 'archived'");
  }

  @Test
  void bracesThatAreNotAReferenceAreLiteralText() {
    SqlExpr jsonLiteral = SqlExpr.raw("payload @> '{\"state\": 1}' AND id = {0}", 1L);
    SqlExpr spacedBrace = SqlExpr.raw("note = '{ 0 }' AND id = {0}", 1L);
    SqlExpr nonDigitBrace = SqlExpr.raw("note = '{x}' AND id = {0}", 1L);

    assertThat(jsonLiteral.arguments()).containsExactly(1L);
    assertThat(spacedBrace.arguments()).containsExactly(1L);
    assertThat(nonDigitBrace.arguments()).containsExactly(1L);
  }

  @Test
  void aFragmentThatReferencesNothingAndTakesNothingIsStillLegal() {
    SqlExpr alwaysFalse = SqlExpr.raw("1 = 0");

    assertThat(alwaysFalse.arguments()).isEmpty();
  }
}
