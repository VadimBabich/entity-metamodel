package io.github.vadimbabich.entitymetamodel.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.vadimbabich.entitymetamodel.runtime.fixtures.Account;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The owned condition vocabulary is pure data built in this module: a predicate names a property,
 * an operator and values, and renders nowhere. That this test compiles and runs here — with no
 * execution module on the classpath — is the contract, since a predicate that needed the renderer
 * to exist would make every builder call a runtime failure instead of a compile-time one.
 *
 * <p>Two call-site rejections are pinned deliberately: both would otherwise produce SQL that runs
 * and quietly returns the wrong rows.
 */
class ConditionVocabularyTest {

  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);

  @Test
  void aComparisonCarriesItsPropertyOperatorAndValue() {
    PropertyRef<Account, String> ownerEmail = ACCOUNT.property("ownerEmail", String.class);

    Condition emailMatches = ownerEmail.is("owner@example.com");

    assertThat(emailMatches)
        .isEqualTo(new Comparison(ownerEmail, Comparison.Operator.EQUAL, "owner@example.com"));
  }

  @Test
  void eachComparisonOperatorIsDistinct() {
    PropertyRef<Account, Long> id = ACCOUNT.property("id", Long.class);
    PropertyRef<Account, String> ownerEmail = ACCOUNT.property("ownerEmail", String.class);

    assertThat(
        List.of(
            id.is(1L),
            id.gt(1L),
            id.gte(1L),
            id.lt(1L),
            id.lte(1L),
            ownerEmail.like("%@example.com")))
        .extracting(condition -> ((Comparison) condition).operator())
        .containsExactly(
            Comparison.Operator.EQUAL,
            Comparison.Operator.GREATER_THAN,
            Comparison.Operator.GREATER_THAN_OR_EQUAL,
            Comparison.Operator.LESS_THAN,
            Comparison.Operator.LESS_THAN_OR_EQUAL,
            Comparison.Operator.LIKE);
  }

  @Test
  void anInclusionCopiesItsValuesSoLaterMutationCannotReachTheCondition() {
    PropertyRef<Account, Long> id = ACCOUNT.property("id", Long.class);
    List<Long> wanted = new ArrayList<>(List.of(1L, 2L));

    Condition idIsOneOf = id.in(wanted);
    wanted.clear();

    assertThat(((Inclusion) idIsOneOf).values()).isEqualTo(List.of(1L, 2L));
  }

  @Test
  void comparingToNullIsRejectedAtTheCallSiteRatherThanRenderedAsEqualsNull() {
    PropertyRef<Account, String> ownerEmail = ACCOUNT.property("ownerEmail", String.class);

    assertThatNullPointerException()
        .isThrownBy(() -> ownerEmail.is(null))
        .withMessageContaining("isNull()");
  }

  @Test
  void anEmptyInclusionIsRejectedRatherThanRenderedAsInvalidSql() {
    PropertyRef<Account, Long> id = ACCOUNT.property("id", Long.class);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> id.in(List.of()))
        .withMessageContaining("empty");
  }

  @Test
  void aJunctionCarriesBothOperandsInOrder() {
    Condition emailMatches = ACCOUNT.property("ownerEmail", String.class).is("owner@example.com");
    Condition idIsKnown = ACCOUNT.property("id", Long.class).gt(0L);

    Condition both = emailMatches.and(idIsKnown);
    Condition either = emailMatches.or(idIsKnown);

    assertThat(both).isEqualTo(new Junction(Junction.Operator.AND, emailMatches, idIsKnown));
    assertThat(either).isEqualTo(new Junction(Junction.Operator.OR, emailMatches, idIsKnown));
  }

  @Test
  void equalityIsValueIdentitySoADerivedStatementCanReuseACondition() {
    PropertyRef<Account, Long> id = ACCOUNT.property("id", Long.class);

    assertThat(id.is(1L)).isEqualTo(id.is(1L));
    assertThat(id.is(1L)).hasSameHashCodeAs(id.is(1L));
    assertThat(id.is(1L)).isNotEqualTo(id.is(2L));
  }

  @Test
  void aRawFragmentCarriesItsPlaceholdersAndValuesSeparately() {
    Condition lowercasedEmail = SqlExpr.raw("lower(owner_email) = ?", "owner@example.com");

    assertThat(lowercasedEmail)
        .isEqualTo(new SqlExpr("lower(owner_email) = ?", List.of("owner@example.com")));
  }

  @Test
  void aRawFragmentWhosePlaceholdersDoNotMatchItsValuesIsRejected() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> SqlExpr.raw("owner_email = ? OR owner_email = ?", "only-one"))
        .withMessageContaining("2 placeholder");

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> SqlExpr.raw("owner_email = ?", "first", "second"))
        .withMessageContaining("1 placeholder");
  }

  @Test
  void aBlankRawFragmentIsRejected() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> SqlExpr.raw("   "))
        .withMessageContaining("blank");
  }

  @Test
  void aConditionOnASecondInstanceOfTheSameTableIsADifferentCondition() {
    PropertyRef<Account, Long> id = ACCOUNT.property("id", Long.class);
    EntityRef<Account> sponsor = ACCOUNT.as("sponsor");

    assertThat(id.of(sponsor).is(1L)).isNotEqualTo(id.is(1L));
  }
}
