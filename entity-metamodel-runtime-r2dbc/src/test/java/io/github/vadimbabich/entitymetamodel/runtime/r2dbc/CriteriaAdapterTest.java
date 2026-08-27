package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.JoinRef;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.AccessGrant;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Membership;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.CriteriaDefinition;
import org.springframework.data.relational.core.query.ValueFunction;
import org.springframework.data.relational.core.sql.SqlIdentifier;

/**
 * The door for filters an application already builds. Their output is accepted unchanged, which is
 * the point: a filter layer that works must not have to be rewritten to use this builder.
 */
class CriteriaAdapterTest {

  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);
  private static final EntityRef<Membership> MEMBERSHIP = EntityRef.of(Membership.class);
  private static final EntityRef<AccessGrant> GRANT = EntityRef.of(AccessGrant.class);

  private final R2dbcMappingContext mappingContext = new R2dbcMappingContext();
  private final QueryRenderer renderer =
      new QueryRenderer(mappingContext, PostgresDialect.INSTANCE);
  private final CriteriaAdapter adapter = new CriteriaAdapter(mappingContext);

  private RenderedStatement accountsMatching(CriteriaDefinition criteria) {
    Condition filter = adapter.toCondition(criteria, ACCOUNT).orElseThrow();

    return renderer.render(FluentSelect.from(ACCOUNT).where(filter));
  }

  @Test
  void anEqualityCriteriaBindsItsValueAgainstTheNamedPropertysColumn() {
    RenderedStatement statement =
        accountsMatching(Criteria.where("ownerEmail").is("owner@example.com"));

    assertThat(statement.sql()).endsWith("WHERE \"account\".\"owner_email\" = $1");
    assertThat(statement.values()).containsExactly("owner@example.com");
  }

  @Test
  void anAndChainKeepsBothTermsAndTheirOrder() {
    RenderedStatement statement =
        accountsMatching(
            Criteria.where("ownerEmail").is("owner@example.com").and("id").greaterThan(10L));

    assertThat(statement.sql())
        .endsWith(
            "WHERE (\"account\".\"owner_email\" = $1) AND (\"account\".\"account_id\" > $2)");
    assertThat(statement.values()).containsExactly("owner@example.com", 10L);
  }

  @Test
  void anOrChainStaysGroupedSoItCannotEscapeASurroundingAnd() {
    CriteriaDefinition eitherAddress =
        Criteria.where("ownerEmail").is("first@example.com").or("ownerEmail")
            .is("second@example.com");

    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(ACCOUNT)
                .where(ACCOUNT.property("id", Long.class).gt(0L))
                .where(adapter.toCondition(eitherAddress, ACCOUNT).orElseThrow()));

    assertThat(statement.sql())
        .endsWith(
            "WHERE (\"account\".\"account_id\" > $1)"
                + " AND ((\"account\".\"owner_email\" = $2)"
                + " OR (\"account\".\"owner_email\" = $3))");
  }

  @Test
  void aGroupedCriteriaBecomesItsOwnNestedCondition() {
    CriteriaDefinition grouped =
        Criteria.where("id").greaterThan(1L)
            .and(
                Criteria.where("ownerEmail").is("first@example.com").or("ownerEmail")
                    .is("second@example.com"));

    RenderedStatement statement = accountsMatching(grouped);

    assertThat(statement.sql())
        .endsWith(
            "WHERE (\"account\".\"account_id\" > $1)"
                + " AND ((\"account\".\"owner_email\" = $2)"
                + " OR (\"account\".\"owner_email\" = $3))");
  }

  @Test
  void aChainFoldsUnderSqlPrecedenceRatherThanLeftToRight() {
    // The substrate emits a chain unbracketed, so `a OR b AND c` executes as `a OR (b AND c)`. Read
    // left to right it is `(a OR b) AND c`, which drops a row matching only `a`.
    RenderedStatement statement =
        accountsMatching(
            Criteria.where("id").is(1L).or("ownerEmail").is("b").and("state").is("ACTIVE"));

    assertThat(statement.sql())
        .endsWith(
            "WHERE (\"account\".\"account_id\" = $1)"
                + " OR ((\"account\".\"owner_email\" = $2)"
                + " AND (\"account\".\"state\" = $3))");
  }

  @Test
  void andBindsTighterThanOrInEitherOrder() {
    // AND binds tighter, so this is `(a AND b) OR c` on both sides — and must stay that way.
    RenderedStatement statement =
        accountsMatching(
            Criteria.where("id").is(1L).and("ownerEmail").is("b").or("state").is("ACTIVE"));

    assertThat(statement.sql())
        .endsWith(
            "WHERE ((\"account\".\"account_id\" = $1)"
                + " AND (\"account\".\"owner_email\" = $2))"
                + " OR (\"account\".\"state\" = $3)");
  }

  @Test
  void aRunOfOneCombinatorIsUnaffectedByPrecedence() {
    assertThat(accountsMatching(Criteria.where("id").is(1L).or("ownerEmail").is("b")
        .or("state").is("ACTIVE")).sql())
        .endsWith(
            "WHERE ((\"account\".\"account_id\" = $1)"
                + " OR (\"account\".\"owner_email\" = $2))"
                + " OR (\"account\".\"state\" = $3)");

    assertThat(accountsMatching(Criteria.where("id").is(1L).and("ownerEmail").is("b")
        .and("state").is("ACTIVE")).sql())
        .endsWith(
            "WHERE ((\"account\".\"account_id\" = $1)"
                + " AND (\"account\".\"owner_email\" = $2))"
                + " AND (\"account\".\"state\" = $3)");
  }

  @Test
  void aGroupJoinsEveryMemberWithTheGroupsOwnCombinator() {
    // Measured against the substrate's mapper: one combinator applies to every member, so a member
    // whose chain ends in `.or(...)` does not make the group disjunctive. Reading it off each
    // member turns `from(scope, search)` into `scope OR search`, and the scope stops constraining.
    CriteriaDefinition searchEndingInOr =
        Criteria.where("ownerEmail").like("%q%").or("state").is("ACTIVE");

    RenderedStatement scoped =
        accountsMatching(
            CriteriaDefinition.from(List.of(Criteria.where("id").is(1L), searchEndingInOr)));

    assertThat(scoped.sql())
        .endsWith(
            "WHERE (\"account\".\"account_id\" = $1)"
                + " AND ((\"account\".\"owner_email\" LIKE $2)"
                + " OR (\"account\".\"state\" = $3))");
  }

  @Test
  void aDisjunctiveGroupJoinsItsMembersWithOr() {
    RenderedStatement statement =
        accountsMatching(
            Criteria.where("id").is(1L)
                .or(
                    List.of(
                        Criteria.where("ownerEmail").is("first@example.com"),
                        Criteria.where("state").is("ACTIVE"))));

    assertThat(statement.sql())
        .endsWith(
            "WHERE (\"account\".\"account_id\" = $1)"
                + " OR ((\"account\".\"owner_email\" = $2)"
                + " OR (\"account\".\"state\" = $3))");
  }

  @Test
  void aConjunctiveGroupJoinsItsMembersWithAnd() {
    RenderedStatement statement =
        accountsMatching(
            Criteria.where("id").is(1L)
                .and(
                    List.of(
                        Criteria.where("ownerEmail").is("first@example.com"),
                        Criteria.where("state").is("ACTIVE"))));

    assertThat(statement.sql())
        .endsWith(
            "WHERE (\"account\".\"account_id\" = $1)"
                + " AND ((\"account\".\"owner_email\" = $2)"
                + " AND (\"account\".\"state\" = $3))");
  }

  @Test
  void everyComparatorThisVocabularyCanSayIsTranslated() {
    assertThat(accountsMatching(Criteria.where("id").not(1L)).sql())
        .endsWith("WHERE NOT (\"account\".\"account_id\" = $1)");
    assertThat(accountsMatching(Criteria.where("id").lessThan(1L)).sql())
        .endsWith("WHERE \"account\".\"account_id\" < $1");
    assertThat(accountsMatching(Criteria.where("id").lessThanOrEquals(1L)).sql())
        .endsWith("WHERE \"account\".\"account_id\" <= $1");
    assertThat(accountsMatching(Criteria.where("id").greaterThanOrEquals(1L)).sql())
        .endsWith("WHERE \"account\".\"account_id\" >= $1");
    assertThat(accountsMatching(Criteria.where("ownerEmail").like("%@example.com")).sql())
        .endsWith("WHERE \"account\".\"owner_email\" LIKE $1");
    assertThat(accountsMatching(Criteria.where("ownerEmail").notLike("%@example.com")).sql())
        .endsWith("WHERE NOT (\"account\".\"owner_email\" LIKE $1)");
    assertThat(accountsMatching(Criteria.where("id").in(List.of(1L, 2L))).sql())
        .endsWith("WHERE \"account\".\"account_id\" IN ($1, $2)");
    assertThat(accountsMatching(Criteria.where("id").notIn(List.of(1L, 2L))).sql())
        .endsWith("WHERE NOT (\"account\".\"account_id\" IN ($1, $2))");
    assertThat(accountsMatching(Criteria.where("ownerEmail").isNull()).sql())
        .endsWith("WHERE \"account\".\"owner_email\" IS NULL");
    assertThat(accountsMatching(Criteria.where("ownerEmail").isNotNull()).sql())
        .endsWith("WHERE NOT (\"account\".\"owner_email\" IS NULL)");
  }

  @Test
  void anEmptySelectionMatchesNothingRatherThanBeingRefused() {
    // What a filter layer produces when a selection is cleared. The substrate renders it as a false
    // condition; refusing it would 500 the first time somebody empties a filter.
    RenderedStatement statement = accountsMatching(Criteria.where("id").in(List.of()));

    assertThat(statement.sql()).endsWith("WHERE 1 = 0");
    assertThat(statement.values()).isEmpty();
  }

  @Test
  void anEmptyExclusionMatchesEverythingBecauseThatIsWhatItExcludesNothingMeans() {
    RenderedStatement statement = accountsMatching(Criteria.where("id").notIn(List.of()));

    assertThat(statement.sql()).endsWith("WHERE NOT (1 = 0)");
    assertThat(statement.values()).isEmpty();
  }

  @Test
  void aRangeBecomesItsTwoBoundsBecauseThatIsWhatBetweenMeans() {
    RenderedStatement statement = accountsMatching(Criteria.where("id").between(1L, 10L));

    assertThat(statement.sql())
        .endsWith(
            "WHERE (\"account\".\"account_id\" >= $1) AND (\"account\".\"account_id\" <= $2)");
    assertThat(statement.values()).containsExactly(1L, 10L);

    assertThat(accountsMatching(Criteria.where("id").notBetween(1L, 10L)).sql())
        .endsWith(
            "WHERE NOT ((\"account\".\"account_id\" >= $1)"
                + " AND (\"account\".\"account_id\" <= $2))");
  }

  @Test
  void aBooleanTestBindsTheValueItAsksAbout() {
    Condition granted =
        adapter.toCondition(Criteria.where("hasBrowseAccess").isTrue(), GRANT).orElseThrow();
    Condition withheld =
        adapter.toCondition(Criteria.where("hasBrowseAccess").isFalse(), GRANT).orElseThrow();

    assertThat(renderer.render(FluentSelect.from(GRANT).where(granted)).values())
        .containsExactly(Boolean.TRUE);
    assertThat(renderer.render(FluentSelect.from(GRANT).where(withheld)).values())
        .containsExactly(Boolean.FALSE);
  }

  @Test
  void anEqualityAgainstNullAsksAboutAbsenceTheWayTheSubstrateDoes() {
    assertThat(accountsMatching(new NullValued(CriteriaDefinition.Comparator.EQ)).sql())
        .endsWith("WHERE \"account\".\"owner_email\" IS NULL");
    assertThat(accountsMatching(new NullValued(CriteriaDefinition.Comparator.NEQ)).sql())
        .endsWith("WHERE NOT (\"account\".\"owner_email\" IS NULL)");
  }

  // By hand because Criteria cannot express it: its builder rejects a null value while the
  // CriteriaDefinition interface declares it nullable, so a converter implementing the interface
  // can hand over exactly this.
  @NullMarked
  private record NullValued(
      CriteriaDefinition.Comparator comparator) implements CriteriaDefinition {

    @Override
    public boolean isGroup() {
      return false;
    }

    @Override
    public List<CriteriaDefinition> getGroup() {
      return List.of();
    }

    @Override
    public SqlIdentifier getColumn() {
      return SqlIdentifier.unquoted("ownerEmail");
    }

    @Override
    public Comparator getComparator() {
      return comparator;
    }

    @Nullable
    @Override
    public Object getValue() {
      return null;
    }

    @Override
    public boolean isIgnoreCase() {
      return false;
    }

    @Nullable
    @Override
    public CriteriaDefinition getPrevious() {
      return null;
    }

    @Override
    public boolean hasPrevious() {
      return false;
    }

    @Override
    public boolean isEmpty() {
      return false;
    }

    @Override
    public Combinator getCombinator() {
      return Combinator.INITIAL;
    }
  }

  @Test
  void aCriteriaMayBeAnchoredToAJoinedInstance() {
    JoinRef<Membership, Account> owner =
        JoinRef.of(
            MEMBERSHIP.property("accountId", Long.class), ACCOUNT.property("id", Long.class));
    Condition filter =
        adapter.toCondition(Criteria.where("ownerEmail").is("owner@example.com"), ACCOUNT)
            .orElseThrow();

    RenderedStatement statement =
        renderer.render(FluentSelect.from(MEMBERSHIP).join(owner).where(filter));

    assertThat(statement.sql()).endsWith("WHERE \"account\".\"owner_email\" = $1");
  }

  @Test
  void anEmptyCriteriaSaysNothingRatherThanMatchingEverything() {
    assertThat(adapter.toCondition(Criteria.empty(), ACCOUNT)).isEmpty();
  }

  @Test
  void aDeferredValueIsRefusedBecauseApplyingItNeedsAnEscaperThisDoorHasNot() {
    // The escaper comes from the comparator and the dialect, and this adapter holds neither, so the
    // lambda would reach the driver as a value.
    ValueFunction<String> escaped = escaper -> escaper.escape("50%") + "%";

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> accountsMatching(Criteria.where("ownerEmail").like(escaped)))
        .withMessageContaining("ValueFunction");
  }

  @Test
  void aValueWrappedInADeprecatedParameterIsRefusedRatherThanCompared() throws Exception {
    // Reflectively because naming the type raises a deprecation warning under -Werror, which is
    // also why the adapter matches it by name.
    Class<?> wrapper = Class.forName("org.springframework.r2dbc.core.Parameter");
    Object emptyValue = wrapper.getMethod("empty", Class.class).invoke(null, String.class);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> accountsMatching(Criteria.where("ownerEmail").is(emptyValue)))
        .withMessageContaining("Parameter");
  }

  @Test
  void aCriteriaNamingSomethingTheEntityDoesNotPersistIsRejected() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> accountsMatching(Criteria.where("nickname").is("nick")))
        .withMessageContaining("nickname");
  }

  @Test
  void aCaseInsensitiveCriteriaIsRefusedRatherThanSilentlyMadeCaseSensitive() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () ->
                accountsMatching(
                    Criteria.where("ownerEmail").is("OWNER@example.com").ignoreCase(true)))
        .withMessageContaining("SqlExpr");
  }

  @Test
  void aMissingCriteriaOrInstanceIsRejectedWhereItIsPassed() {
    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> adapter.toCondition(null, ACCOUNT));
    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> adapter.toCondition(Criteria.empty(), null));
    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> new CriteriaAdapter(null));
  }

  @Test
  void theAdapterIsUsableWithoutHavingToUnwrapAnOptionalByHand() {
    Optional<Condition> nothing = adapter.toCondition(Criteria.empty(), ACCOUNT);

    assertThat(nothing).isEmpty();
    assertThat(adapter.toCondition(Criteria.where("id").is(1L), ACCOUNT)).isPresent();
  }
}
