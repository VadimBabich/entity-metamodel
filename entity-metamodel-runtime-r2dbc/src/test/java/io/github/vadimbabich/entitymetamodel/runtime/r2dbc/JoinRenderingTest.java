package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.JoinRef;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import io.github.vadimbabich.entitymetamodel.runtime.SqlExpr;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Employee;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Membership;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;

/**
 * A {@link JoinRef} names a relationship, so joining it brings the <em>target</em> instance in
 * while the source must already be there. Traversing one relationship N times is therefore a matter
 * of naming N instances, which is why refs carry instance identity.
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
  void anInstanceCanBeJoinedOnAConditionTheCallerStates() {
    Condition sameAccount =
        MEMBERSHIP.property("accountId", Long.class)
            .eq(ACCOUNT.property("id", Long.class));

    RenderedStatement statement =
        renderer.render(FluentSelect.from(MEMBERSHIP).join(ACCOUNT).on(sameAccount));

    assertThat(statement.sql())
        .contains(
            "FROM \"memberships\" \"membership\""
                + " JOIN \"accounts\" \"account\""
                + " ON \"membership\".\"account_id\" = \"account\".\"account_id\"");
  }

  @Test
  void aStatedConditionMayCarryBindValuesInsideTheOnClause() {
    Condition sameActiveAccount =
        MEMBERSHIP.property("accountId", Long.class)
            .eq(ACCOUNT.property("id", Long.class))
            .and(ACCOUNT.property("ownerEmail", String.class).is("owner@example.com"));

    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(MEMBERSHIP)
                .join(ACCOUNT)
                .on(sameActiveAccount)
                .where(MEMBERSHIP.property("id", Long.class).gt(10L)));

    assertThat(statement.sql())
        .contains(
            "ON (\"membership\".\"account_id\" = \"account\".\"account_id\")"
                + " AND (\"account\".\"owner_email\" = $1)")
        .endsWith("WHERE \"membership\".\"membership_id\" > $2");
    assertThat(statement.values()).containsExactly("owner@example.com", 10L);
  }

  @Test
  void aStatedConditionMayBeAnExpressionTheTypedVocabularyCannotSay() {
    EntityRef<Account> sponsor = ACCOUNT.as("sponsor");
    PropertyRef<Membership, Long> accountId = MEMBERSHIP.property("accountId", Long.class);
    PropertyRef<Membership, Long> sponsorAccountId =
        MEMBERSHIP.property("sponsorAccountId", Long.class);

    Condition eitherAccount =
        SqlExpr.raw(
            "COALESCE(?, ?) = ?", sponsorAccountId, accountId,
            ACCOUNT.property("id", Long.class).of(sponsor));

    RenderedStatement statement =
        renderer.render(FluentSelect.from(MEMBERSHIP).leftOuterJoin(sponsor).on(eitherAccount));

    assertThat(statement.sql())
        .contains(
            "LEFT OUTER JOIN \"accounts\" \"account_sponsor\""
                + " ON COALESCE(\"membership\".\"sponsor_account_id\","
                + " \"membership\".\"account_id\")"
                + " = \"account_sponsor\".\"account_id\"");
    assertThat(statement.values()).isEmpty();
  }

  @Test
  void aStatedJoinRejectsAnInstanceTheStatementAlreadyCarries() {
    Condition sameAccount =
        MEMBERSHIP.property("accountId", Long.class).eq(ACCOUNT.property("id", Long.class));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () ->
                FluentSelect.from(MEMBERSHIP)
                    .join(account())
                    .join(ACCOUNT)
                    .on(sameAccount))
        .withMessageContaining("already part of this statement");
  }

  @Test
  void asecondHopOfASelfReferencingRelationshipAnchorsToTheFirstHopsTarget() {
    // The second hop must start where the first ended; anchored to the FROM instance it renders
    // valid SQL that returns the manager twice.
    EntityRef<Employee> employee = EntityRef.of(Employee.class);
    EntityRef<Employee> manager = employee.as("mgr");
    EntityRef<Employee> managersManager = employee.as("mgr2");
    JoinRef<Employee, Employee> reportsTo =
        JoinRef.of(
            employee.property("managerId", Long.class), employee.property("id", Long.class));

    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(employee)
                .join(reportsTo, manager)
                .join(reportsTo, manager, managersManager));

    assertThat(statement.sql())
        .contains("JOIN \"employees\" \"employee_mgr\""
            + " ON \"employee\".\"manager_id\" = \"employee_mgr\".\"id\"")
        .contains("JOIN \"employees\" \"employee_mgr2\""
            + " ON \"employee_mgr\".\"manager_id\" = \"employee_mgr2\".\"id\"");
  }

  @Test
  void aRelationshipWhoseSourceInstanceIsAmbiguousIsRefusedRatherThanGuessed() {
    // Two instances of the source type, so which one this hop starts from is unknowable — and
    // guessing renders SQL that executes and answers a different question.
    EntityRef<Employee> employee = EntityRef.of(Employee.class);
    EntityRef<Employee> manager = employee.as("mgr");
    JoinRef<Employee, Employee> reportsTo =
        JoinRef.of(
            employee.property("managerId", Long.class), employee.property("id", Long.class));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () ->
                FluentSelect.from(employee)
                    .join(reportsTo, manager)
                    .join(reportsTo, employee.as("mgr2")))
        .withMessageContaining("Employee");
  }

  @Test
  void aCrossTypeRelationshipAnchorsToTheInstanceItNamesEvenWithASecondSourcePresent() {
    // A second Account instance for an unrelated hop. This relationship targets a Membership, so
    // the declared anchor is the contract and refusing would break a shape that renders correctly.
    EntityRef<Account> sponsor = ACCOUNT.as("sponsor");
    PropertyRef<Account, Long> accountId = ACCOUNT.property("id", Long.class);
    JoinRef<Account, Membership> memberships =
        JoinRef.of(accountId, MEMBERSHIP.property("accountId", Long.class));

    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(ACCOUNT)
                .join(sponsor)
                .on(accountId.eq(accountId.of(sponsor)))
                .join(memberships));

    assertThat(statement.sql())
        .contains("JOIN \"memberships\" \"membership\""
            + " ON \"account\".\"account_id\" = \"membership\".\"account_id\"");
  }

  @Test
  void aRelationshipWhoseDeclaredSourceIsAbsentSaysSoRatherThanReportingItPresent() {
    EntityRef<Employee> employee = EntityRef.of(Employee.class);
    JoinRef<Employee, Employee> reportsTo =
        JoinRef.of(
            employee.property("managerId", Long.class), employee.property("id", Long.class));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> FluentSelect.from(employee.as("mgr")).join(reportsTo))
        .withMessageContaining("does not carry");
  }

  @Test
  void namingBothInstancesStillRejectsAMissingArgumentWhereItIsPassed() {
    EntityRef<Employee> employee = EntityRef.of(Employee.class);
    JoinRef<Employee, Employee> reportsTo =
        JoinRef.of(
            employee.property("managerId", Long.class), employee.property("id", Long.class));
    FluentSelect<Employee> employees = FluentSelect.from(employee);

    assertThatNullPointerException()
        .isThrownBy(() -> employees.join(reportsTo, null, employee.as("mgr")))
        .withMessageContaining("sourceInstance");
    assertThatNullPointerException()
        .isThrownBy(() -> employees.leftOuterJoin(null, employee, employee.as("mgr")))
        .withMessageContaining("relationship");
  }

  @Test
  void anAbsentDeclaredSourceSaysWhatToDoAcrossTypesToo() {
    // Hop 1 lands on a named Membership, so hop 2's declared source is not in the statement. The
    // same failure as the self-referencing case deserves the same answer, not an unknown table.
    EntityRef<Account> sponsor = ACCOUNT.as("sponsor");
    JoinRef<Account, Membership> memberships =
        JoinRef.of(
            ACCOUNT.property("id", Long.class),
            MEMBERSHIP.property("accountId", Long.class));

    FluentSelect<Account> throughANamedMembership =
        FluentSelect.from(ACCOUNT).join(memberships, MEMBERSHIP.as("m2"));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> throughANamedMembership.join(sponsorAccount(), sponsor))
        .withMessageContaining("does not carry");
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

    // Distinct types, so the duplicate-instance guard does not fire — but one default alias, and
    // the driver would blame the database.
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
