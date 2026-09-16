package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.ExpressionSort;
import io.github.vadimbabich.entitymetamodel.runtime.JoinRef;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import io.github.vadimbabich.entitymetamodel.runtime.SqlExpr;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.AccessGrant;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.AccountState;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Membership;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.CriteriaDefinition;

/**
 * The module's shared test vocabulary, and over it the query shapes a production Spring Data R2DBC
 * application builds. Join topology, bind placement and composition order come from real
 * repositories; the tables and columns are this module's own.
 *
 * <p>Shared because the golden suite pins what these shapes render while the invariants suite pins
 * the properties that keep them correct: a scenario that drifted between the two would let a golden
 * agree with an invariant about the wrong statement.
 */
final class ProductionPatterns {

  static final EntityRef<Membership> MEMBERSHIP = EntityRef.of(Membership.class);
  static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);
  static final EntityRef<Account> SPONSOR = ACCOUNT.as("sponsor");
  static final EntityRef<AccessGrant> GRANT = EntityRef.of(AccessGrant.class);
  static final EntityRef<AccessGrant> LICENCE_GRANT = GRANT.as("licence");

  static final long PRINCIPAL_ID = 4711L;

  private ProductionPatterns() {
  }

  static JoinRef<Membership, Account> owningAccount() {
    return JoinRef.of(membershipAccountId(), accountKeyOf(ACCOUNT));
  }

  static JoinRef<Membership, Account> sponsoringAccount() {
    return JoinRef.of(membershipSponsorAccountId(), accountKeyOf(ACCOUNT));
  }

  // The to-many side, so traversing it multiplies rows.
  static JoinRef<Account, Membership> memberships() {
    return JoinRef.of(accountKeyOf(ACCOUNT), membershipAccountId());
  }

  static PropertyRef<Account, String> ownerEmail() {
    return ACCOUNT.property("ownerEmail", String.class);
  }

  static PropertyRef<Account, Long> accountId() {
    return accountKeyOf(ACCOUNT);
  }

  /**
   * The dominant shape: a listing scoped to one principal through a permission view. All four
   * instances are projected because the caller reads the access flags off the same row.
   */
  static FluentSelect<Membership> scopedListing() {
    return FluentSelect.from(MEMBERSHIP)
        .alsoSelect(ACCOUNT)
        .alsoSelect(SPONSOR)
        .alsoSelect(GRANT)
        .join(ACCOUNT)
        .on(membershipAccountId().eq(accountKeyOf(ACCOUNT)))
        .leftOuterJoin(SPONSOR)
        .on(membershipSponsorAccountId().eq(accountKeyOf(SPONSOR)))
        .join(GRANT)
        .on(grantedToPrincipal(GRANT, membershipAccountId().eq(grantKeyOf(GRANT))));
  }

  static FluentSelect<Membership> pagedScopedListing() {
    return scopedListing()
        .orderBy(ownerEmail().asc())
        .limit(20)
        .offset(40);
  }

  /**
   * The licence shape: one permission view joined twice. The raw door carries the fallback
   * expression the second join is keyed on, and the library writes both aliases.
   */
  static FluentSelect<Membership> doubleGrantSelfJoin() {
    Condition licenceKeyedOnFallback =
        SqlExpr.raw(
            "{0} = COALESCE({1}, {2})",
            grantKeyOf(LICENCE_GRANT),
            membershipSponsorAccountId(),
            membershipAccountId());

    return FluentSelect.from(MEMBERSHIP)
        .join(GRANT)
        .on(grantedToPrincipal(GRANT, membershipAccountId().eq(grantKeyOf(GRANT))))
        .join(LICENCE_GRANT)
        .on(grantedToPrincipal(LICENCE_GRANT, licenceKeyedOnFallback));
  }

  /**
   * A scoping predicate the caller owns, narrowed by a received filter. The received half is a
   * disjunction, which is what makes the grouping load-bearing: ungrouped it would widen the scope
   * instead of narrowing it.
   */
  static FluentSelect<Account> scopeNarrowedBySearch() {
    Condition search = ownerEmail().like("%first%").or(ownerEmail().like("%second%"));

    return FluentSelect.from(ACCOUNT).where(accountKeyOf(ACCOUNT).gt(0L).and(search));
  }

  /** The filter a search endpoint receives already built, in the deepest shape one produces. */
  static CriteriaDefinition receivedFilter() {
    return Criteria.empty()
        .and(Criteria.where("id").between(1L, 2L).or("id").is(3L))
        .and("ownerEmail")
        .isNotNull();
  }

  static FluentSelect<Account> scopeNarrowedByReceivedFilter(Condition received) {
    return FluentSelect.from(ACCOUNT)
        .where(ACCOUNT.property("state", AccountState.class).is(AccountState.ACTIVE).and(received));
  }

  /** query-recipes.md's expanded keyset predicate, for an all-ascending sort. */
  static <E, L, T> Condition expandedKeysetAfter(
      PropertyRef<E, L> leadingKey,
      L leadingCursor,
      PropertyRef<E, T> terminalKey,
      T terminalCursor) {

    return leadingKey
        .gt(leadingCursor)
        .or(leadingKey.is(leadingCursor).and(terminalKey.gt(terminalCursor)));
  }

  /** The same shape for a mixed-direction sort, which is the only form that can express one. */
  static <E, L, T> Condition expandedKeysetAfterLeadingDescendingTerminalAscending(
      PropertyRef<E, L> leadingKey,
      L leadingCursor,
      PropertyRef<E, T> terminalKey,
      T terminalCursor) {

    return leadingKey
        .lt(leadingCursor)
        .or(leadingKey.is(leadingCursor).and(terminalKey.gt(terminalCursor)));
  }

  /**
   * query-recipes.md's row-value keyset predicate, for a sort that runs one direction throughout.
   * The comparison inverts with the sort, so both forms are pinned: reaching for the wrong one is
   * silent.
   */
  static <E, L, T> Condition rowValueKeysetAfter(
      PropertyRef<E, L> leadingKey,
      L leadingCursor,
      PropertyRef<E, T> terminalKey,
      T terminalCursor) {

    return SqlExpr.raw(
        "({0}, {1}) > ({2}, {3})", leadingKey, terminalKey, leadingCursor, terminalCursor);
  }

  static <E, L, T> Condition rowValueKeysetBefore(
      PropertyRef<E, L> leadingKey,
      L leadingCursor,
      PropertyRef<E, T> terminalKey,
      T terminalCursor) {

    return SqlExpr.raw(
        "({0}, {1}) < ({2}, {3})", leadingKey, terminalKey, leadingCursor, terminalCursor);
  }

  /**
   * The unsafe-sort escape. Pinned because it is rarely reached for: an escape with no recorded
   * rendering is an escape nobody has read.
   */
  static FluentSelect<Account> listingSortedByAnExpression() {
    return FluentSelect.from(ACCOUNT)
        .orderBy(ExpressionSort.desc(SqlExpr.raw("length({0})", ownerEmail())));
  }

  // The permission join's ON. Its key is a column equality ordinarily, an expression in the
  // licence case, so the caller passes it in.
  private static Condition grantedToPrincipal(EntityRef<AccessGrant> grant, Condition keyedOn) {
    return keyedOn
        .and(grant.property("principalId", Long.class).is(PRINCIPAL_ID))
        .and(grant.property("hasBrowseAccess", Boolean.class).is(true));
  }

  static PropertyRef<Membership, Long> membershipAccountId() {
    return MEMBERSHIP.property("accountId", Long.class);
  }

  static PropertyRef<Membership, Long> membershipId() {
    return MEMBERSHIP.property("id", Long.class);
  }

  private static PropertyRef<Membership, Long> membershipSponsorAccountId() {
    return MEMBERSHIP.property("sponsorAccountId", Long.class);
  }

  private static PropertyRef<Account, Long> accountKeyOf(EntityRef<Account> instance) {
    return instance.property("id", Long.class);
  }

  private static PropertyRef<AccessGrant, Long> grantKeyOf(EntityRef<AccessGrant> instance) {
    return instance.property("accountId", Long.class);
  }
}
