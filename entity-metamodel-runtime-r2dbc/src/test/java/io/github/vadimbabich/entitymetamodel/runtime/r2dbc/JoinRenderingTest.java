package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.JoinRef;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Membership;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;

/**
 * A {@link JoinRef} names a relationship, so joining it brings the relationship's <em>target</em>
 * instance into the statement while the source must already be there. Traversing one
 * relationship N times is therefore a matter of naming N target instances, which is the whole
 * reason refs carry instance identity.
 *
 * <p>Two rejections are pinned because both would otherwise produce a statement that executes and
 * returns rows from the wrong table.
 */
class JoinRenderingTest {

  private static final EntityRef<Membership> MEMBERSHIP = EntityRef.of(Membership.class);
  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);

  private final QueryRenderer renderer =
      new QueryRenderer(new R2dbcMappingContext(), PostgresDialect.INSTANCE);

  private static JoinRef<Membership, Account> account() {
    return JoinRef.of(
        MEMBERSHIP.property("accountId", Long.class), ACCOUNT.property("id", Long.class));
  }

  private static JoinRef<Membership, Account> sponsorAccount() {
    return JoinRef.of(
        MEMBERSHIP.property("sponsorAccountId", Long.class), ACCOUNT.property("id", Long.class));
  }

  @Test
  void joiningARelationshipAddsItsTargetTableUnderAnOnCondition() {
    RenderedStatement statement = renderer.render(FluentSelect.from(MEMBERSHIP).join(account()));

    assertThat(statement.sql())
        .contains(
            "FROM \"memberships\" \"membership\""
                + " JOIN \"accounts\" \"account\""
                + " ON \"membership\".\"account_id\" = \"account\".\"account_id\"");
  }

  @Test
  void aLeftOuterJoinKeepsRowsWithoutAMatch() {
    RenderedStatement statement =
        renderer.render(FluentSelect.from(MEMBERSHIP).leftOuterJoin(account()));

    assertThat(statement.sql())
        .contains(
            "FROM \"memberships\" \"membership\""
                + " LEFT OUTER JOIN \"accounts\" \"account\""
                + " ON \"membership\".\"account_id\" = \"account\".\"account_id\"");
  }

  @Test
  void oneRelationshipTraversedTwiceRendersTwoAliasedTables() {
    EntityRef<Account> sponsor = ACCOUNT.as("sponsor");

    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(MEMBERSHIP).join(account()).join(sponsorAccount(), sponsor));

    assertThat(statement.sql())
        .contains(
            "JOIN \"accounts\" \"account\""
                + " ON \"membership\".\"account_id\" = \"account\".\"account_id\"")
        .contains(
            "JOIN \"accounts\" \"account_sponsor\""
                + " ON \"membership\".\"sponsor_account_id\" = \"account_sponsor\".\"account_id\"");
  }

  @Test
  void joiningTheSameInstanceTwiceIsRejected() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> FluentSelect.from(MEMBERSHIP).join(account()).join(sponsorAccount()))
        .withMessageContaining("already part of this statement");
  }

  @Test
  void joiningTheInstanceAlreadySelectedFromIsRejected() {
    JoinRef<Membership, Membership> selfReference =
        JoinRef.of(
            MEMBERSHIP.property("accountId", Long.class),
            MEMBERSHIP.property("accountId", Long.class));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> FluentSelect.from(MEMBERSHIP).join(selfReference))
        .withMessageContaining("already part of this statement");
  }

  @Test
  void aFilterMayNameAPropertyOfAJoinedInstance() {
    PropertyRef<Account, String> ownerEmail = ACCOUNT.property("ownerEmail", String.class);

    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(MEMBERSHIP)
                .join(account())
                .where(ownerEmail.is("owner@example.com")));

    assertThat(statement.sql()).endsWith("WHERE \"account\".\"owner_email\" = $1");
    assertThat(statement.values()).containsExactly("owner@example.com");
  }

  @Test
  void aPropertyOfAnInstanceThatIsNotInTheStatementIsRejected() {
    PropertyRef<Account, String> ownerEmail = ACCOUNT.property("ownerEmail", String.class);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () -> renderer.render(FluentSelect.from(MEMBERSHIP).where(ownerEmail.is("x"))))
        .withMessageContaining("not part of this statement");
  }

  @Test
  void twoEntityTypesSharingASimpleNameAreRejectedRatherThanRenderedOverEachOther() {
    EntityRef<io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.legacy.Account> legacy =
        EntityRef.of(io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.legacy.Account.class);

    // Distinct types, so the duplicate-instance guard does not fire — but one default alias, so
    // the statement would carry the same alias twice and the driver would blame the database.
    JoinRef<Account, ?> sameName =
        JoinRef.of(ACCOUNT.property("id", Long.class), legacy.property("id", Long.class));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> renderer.render(FluentSelect.from(ACCOUNT).join(sameName)))
        .withMessageContaining("account");
  }

  @Test
  void joinsRenderInTheOrderTheyWereAdded() {
    EntityRef<Account> sponsor = ACCOUNT.as("sponsor");

    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(MEMBERSHIP).join(sponsorAccount(), sponsor).join(account()));

    assertThat(statement.sql())
        .containsSubsequence(
            "JOIN \"accounts\" \"account_sponsor\"", "JOIN \"accounts\" \"account\"");
  }
}
