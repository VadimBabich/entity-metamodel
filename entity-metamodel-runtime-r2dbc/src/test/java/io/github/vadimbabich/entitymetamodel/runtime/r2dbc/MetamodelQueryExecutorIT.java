package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;

import static io.r2dbc.spi.ConnectionFactoryOptions.DATABASE;
import static io.r2dbc.spi.ConnectionFactoryOptions.DRIVER;
import static io.r2dbc.spi.ConnectionFactoryOptions.HOST;
import static io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD;
import static io.r2dbc.spi.ConnectionFactoryOptions.PORT;
import static io.r2dbc.spi.ConnectionFactoryOptions.USER;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.JoinRef;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import io.github.vadimbabich.entitymetamodel.runtime.SqlExpr;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.AccountState;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Ghost;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Membership;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Order;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

/**
 * Execution against a real PostgreSQL. Rendering can be proved on paper; that the database accepts
 * the SQL, applies the binds and reports the counts we expect cannot be, which is the whole reason
 * this layer exists.
 *
 * <p>{@link #aValueThatLooksLikeSqlCannotChangeWhatTheQueryMeans()} is the strongest form of the
 * injection assertion available: the hostile string is stored in the database and queried past,
 * rather than merely inspected in a rendered statement.
 */
@EnabledIf("dockerIsAvailableOrRequired")
class MetamodelQueryExecutorIT {

  /**
   * Skips the class on a machine without Docker, so {@code mvn verify} — the pre-commit gate —
   * reports a skip with a reason rather than a red build that reads like a code failure. Never
   * skips in CI: there a missing daemon is a broken runner, and passing silently would retire this
   * whole layer without anyone noticing.
   */
  static boolean dockerIsAvailableOrRequired() {
    return DockerClientFactory.instance().isDockerAvailable() || System.getenv("CI") != null;
  }

  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);
  private static final EntityRef<Membership> MEMBERSHIP = EntityRef.of(Membership.class);
  private static final EntityRef<Ghost> GHOST = EntityRef.of(Ghost.class);

  private static final String INJECTION_ATTEMPT = "x' OR '1'='1";

  private static PostgreSQLContainer postgres;
  private static DatabaseClient databaseClient;
  private static MetamodelQueryExecutor executor;

  @BeforeAll
  static void startDatabase() {
    postgres = new PostgreSQLContainer("postgres:16-alpine");
    postgres.start();

    databaseClient = DatabaseClient.create(connectionFactoryFor(postgres));

    // One mapping context, shared: the renderer names the columns and the converter reads them
    // back, so two contexts that disagreed would project labels nothing claims.
    R2dbcMappingContext mappingContext = new R2dbcMappingContext();
    executor =
        new MetamodelQueryExecutor(
            databaseClient,
            new QueryRenderer(mappingContext, PostgresDialect.INSTANCE),
            new MappingR2dbcConverter(mappingContext));

    // block() in a fixture is the sanctioned exception: the schema is a precondition, not the
    // thing under test.
    execute(
        "create table accounts (account_id bigint primary key, owner_email varchar(255),"
            + " state varchar(16))");
    execute(
        "create table memberships (membership_id bigint primary key, account_id bigint,"
            + " sponsor_account_id bigint)");

    insertAccount(1L, "first@example.com", AccountState.ACTIVE);
    insertAccount(2L, "second@example.com", AccountState.ACTIVE);
    insertAccount(3L, INJECTION_ATTEMPT, AccountState.CLOSED);

    execute("insert into memberships values (10, 1, 2)");
    execute("insert into memberships values (11, 1, 2)");
    execute("insert into memberships values (12, 2, 1)");

    // Both foreign keys dangle, which is what gives the outer-join tests a parent with no
    // counterpart. Without it they assert their own names without ever exercising them.
    execute("insert into memberships values (13, 999, 888)");

    // A reserved-word entity name and a mixed-case column: the two identifier hazards no other
    // fixture has, because every other fixture name happens to be safe.
    execute("create table orders (id bigint primary key, \"placedBy\" varchar(255))");
    execute("insert into orders values (1, 'someone')");
  }

  @AfterAll
  static void stopDatabase() {
    postgres.stop();
  }

  private static ConnectionFactory connectionFactoryFor(PostgreSQLContainer container) {
    return ConnectionFactories.get(
        ConnectionFactoryOptions.builder()
            .option(DRIVER, "postgresql")
            .option(HOST, container.getHost())
            .option(PORT, container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT))
            .option(DATABASE, container.getDatabaseName())
            .option(USER, container.getUsername())
            .option(PASSWORD, container.getPassword())
            .build());
  }

  private static void execute(String sql) {
    databaseClient.sql(sql).fetch().rowsUpdated().block();
  }

  /**
   * Bound rather than concatenated. One of these addresses is a quote-carrying injection attempt,
   * and writing it into the statement text would break the fixture in exactly the way the code
   * under test exists to prevent.
   */
  private static void insertAccount(Long id, String email, AccountState state) {
    databaseClient.sql("insert into accounts values ($1, $2, $3)")
        .bind(0, id)
        .bind(1, email)
        .bind(2, state.name())
        .fetch()
        .rowsUpdated()
        .block();
  }

  private static PropertyRef<Account, String> ownerEmail() {
    return ACCOUNT.property("ownerEmail", String.class);
  }

  @Test
  void countReportsEveryRowWhenNothingIsFiltered() {
    StepVerifier.create(executor.count(FluentSelect.from(ACCOUNT)))
        .expectNext(3L)
        .verifyComplete();
  }

  @Test
  void countReportsOnlyTheRowsTheFilterKeeps() {
    StepVerifier.create(
            executor.count(FluentSelect.from(ACCOUNT).where(ownerEmail().is("first@example.com"))))
        .expectNext(1L)
        .verifyComplete();
  }

  @Test
  void countAppliesAnInclusionAcrossSeveralBinds() {
    StepVerifier.create(
            executor.count(
                FluentSelect.from(ACCOUNT)
                    .where(
                        ACCOUNT.property("id", Long.class).in(List.of(1L, 2L)))))
        .expectNext(2L)
        .verifyComplete();
  }

  @Test
  void countCountsJoinedRowsRatherThanDistinctParents() {
    JoinRef<Membership, Account> account =
        JoinRef.of(
            MEMBERSHIP.property("accountId", Long.class), ACCOUNT.property("id", Long.class));

    StepVerifier.create(executor.count(FluentSelect.from(MEMBERSHIP).join(account)))
        .expectNext(3L)
        .verifyComplete();
  }

  @Test
  void aLeftOuterJoinKeepsParentsWithoutAMatch() {
    JoinRef<Membership, Account> sponsor =
        JoinRef.of(
            MEMBERSHIP.property("sponsorAccountId", Long.class),
            ACCOUNT.property("id", Long.class));

    // Four memberships, one of whose sponsor does not exist: an inner join would drop it.
    StepVerifier.create(executor.count(FluentSelect.from(MEMBERSHIP).leftOuterJoin(sponsor)))
        .expectNext(4L)
        .verifyComplete();

    StepVerifier.create(executor.count(FluentSelect.from(MEMBERSHIP).join(sponsor)))
        .expectNext(3L)
        .verifyComplete();
  }

  @Test
  void aValueThatLooksLikeSqlCannotChangeWhatTheQueryMeans() {
    // The hostile string is a real row. Filtering for a different value must not match it, and
    // must not turn the predicate into a tautology that matches everything.
    StepVerifier.create(
            executor.count(FluentSelect.from(ACCOUNT).where(ownerEmail().is(INJECTION_ATTEMPT))))
        .expectNext(1L)
        .verifyComplete();

    StepVerifier.create(
            executor.count(FluentSelect.from(ACCOUNT).where(ownerEmail().is("no-such-address"))))
        .expectNext(0L)
        .verifyComplete();
  }

  @Test
  void aRawFragmentExecutesWithItsValuesStillBound() {
    StepVerifier.create(
            executor.count(
                FluentSelect.from(ACCOUNT)
                    .where(SqlExpr.raw("lower(owner_email) = ?", "first@example.com"))))
        .expectNext(1L)
        .verifyComplete();
  }

  @Test
  void existsAnswersWithoutCountingTheWholeTable() {
    StepVerifier.create(
            executor.exists(
                FluentSelect.from(ACCOUNT).where(ownerEmail().is("second@example.com"))))
        .expectNext(true)
        .verifyComplete();

    StepVerifier.create(
            executor.exists(FluentSelect.from(ACCOUNT).where(ownerEmail().is("no-such-address"))))
        .expectNext(false)
        .verifyComplete();
  }

  @Test
  void everyRowIsHydratedIntoItsEntity() {
    StepVerifier.create(
            executor.all(FluentSelect.from(ACCOUNT).where(ownerEmail().is("first@example.com"))))
        .assertNext(
            account -> {
              assertThat(account.id).isEqualTo(1L);
              assertThat(account.ownerEmail).isEqualTo("first@example.com");
            })
        .verifyComplete();
  }

  @Test
  void rowsArriveInTheOrderTheStatementAsksFor() {
    StepVerifier.create(
            executor.all(
                FluentSelect.from(ACCOUNT)
                    .where(ACCOUNT.property("id", Long.class).in(List.of(1L, 2L)))
                    .orderBy(ACCOUNT.property("id", Long.class).desc())))
        .assertNext(account -> assertThat(account.id).isEqualTo(2L))
        .assertNext(account -> assertThat(account.id).isEqualTo(1L))
        .verifyComplete();
  }

  @Test
  void hydrationReadsOnlyTheSelectedInstancesColumnsWhenAnotherTableIsJoined() {
    JoinRef<Membership, Account> account =
        JoinRef.of(
            MEMBERSHIP.property("accountId", Long.class), ACCOUNT.property("id", Long.class));

    StepVerifier.create(
            executor.all(
                FluentSelect.from(MEMBERSHIP)
                    .join(account)
                    .where(ownerEmail().is("second@example.com"))
                    .orderBy(MEMBERSHIP.property("id", Long.class).asc())))
        .assertNext(membership -> assertThat(membership.id).isEqualTo(12L))
        .verifyComplete();
  }

  @Test
  void listCollectsThePageIntoOneValue() {
    StepVerifier.create(
            executor.list(
                FluentSelect.from(ACCOUNT).orderBy(ACCOUNT.property("id", Long.class).asc())))
        .assertNext(accounts -> assertThat(accounts).hasSize(3))
        .verifyComplete();
  }

  @Test
  void firstTakesTheLeadingRowAndIsEmptyWhenNothingMatches() {
    StepVerifier.create(
            executor.first(
                FluentSelect.from(ACCOUNT).orderBy(ACCOUNT.property("id", Long.class).asc())))
        .assertNext(account -> assertThat(account.id).isEqualTo(1L))
        .verifyComplete();

    StepVerifier.create(
            executor.first(FluentSelect.from(ACCOUNT).where(ownerEmail().is("no-such-address"))))
        .verifyComplete();
  }

  @Test
  void oneRefusesToChooseBetweenSeveralMatches() {
    StepVerifier.create(executor.one(FluentSelect.from(ACCOUNT)))
        .expectError()
        .verify();

    StepVerifier.create(
            executor.one(FluentSelect.from(ACCOUNT).where(ownerEmail().is("first@example.com"))))
        .assertNext(account -> assertThat(account.id).isEqualTo(1L))
        .verifyComplete();
  }

  @Test
  void anEmptyResultIsAnEmptyPublisherRatherThanAnError() {
    StepVerifier.create(
            executor.all(FluentSelect.from(ACCOUNT).where(ownerEmail().is("no-such-address"))))
        .verifyComplete();
  }

  /** What a multi-entity row is read into — the parent/child shape the design names for J7. */
  private record MembershipParties(Membership membership, Account owner, Account sponsor) {}

  @Test
  void oneRowYieldsTwoInstancesOfTheSameTableWithTheirOwnValues() {
    EntityRef<Account> sponsorInstance = ACCOUNT.as("sponsor");
    JoinRef<Membership, Account> owner =
        JoinRef.of(
            MEMBERSHIP.property("accountId", Long.class), ACCOUNT.property("id", Long.class));
    JoinRef<Membership, Account> sponsor =
        JoinRef.of(
            MEMBERSHIP.property("sponsorAccountId", Long.class),
            ACCOUNT.property("id", Long.class));

    FluentSelect<Membership> parties =
        FluentSelect.from(MEMBERSHIP)
            .join(owner)
            .join(sponsor, sponsorInstance)
            .alsoSelect(ACCOUNT)
            .alsoSelect(sponsorInstance)
            .where(MEMBERSHIP.property("id", Long.class).is(10L));

    StepVerifier.create(
            executor.all(
                parties,
                row ->
                    new MembershipParties(
                        row.read(MEMBERSHIP), row.read(ACCOUNT), row.read(sponsorInstance))))
        .assertNext(
            party -> {
              assertThat(party.membership().id).isEqualTo(10L);
              assertThat(party.owner().id).isEqualTo(1L);
              assertThat(party.owner().ownerEmail).isEqualTo("first@example.com");
              assertThat(party.sponsor().id).isEqualTo(2L);
              assertThat(party.sponsor().ownerEmail).isEqualTo("second@example.com");
            })
        .verifyComplete();
  }

  @Test
  void anOuterJoinThatMatchedNothingReadsAsAbsentRatherThanAsABlankEntity() {
    JoinRef<Membership, Account> owner =
        JoinRef.of(
            MEMBERSHIP.property("accountId", Long.class), ACCOUNT.property("id", Long.class));

    FluentSelect<Membership> unmatched =
        FluentSelect.from(MEMBERSHIP)
            .leftOuterJoin(owner)
            .alsoSelect(ACCOUNT)
            .where(MEMBERSHIP.property("id", Long.class).is(13L));

    // One row still arrives — the outer join kept the membership — and the account it points at
    // does not exist, so reading it back reports absence instead of an all-null entity.
    StepVerifier.create(executor.all(unmatched, row -> row.readOptional(ACCOUNT)))
        .assertNext(absentAccount -> assertThat(absentAccount).isEmpty())
        .verifyComplete();
  }

  @Test
  void anEnumTypedPropertyCanBeFilteredOn() {
    // The driver cannot encode an enum. Domain conversion is the converter's job, and the bind
    // path has to consult it or every enum-typed filter fails at execution.
    StepVerifier.create(
            executor.count(
                FluentSelect.from(ACCOUNT)
                    .where(ACCOUNT.property("state", AccountState.class).is(AccountState.CLOSED))))
        .expectNext(1L)
        .verifyComplete();
  }

  @Test
  void aMixedCaseAliasQualifierStillHydrates() {
    // The database folds the unquoted alias, so a qualifier that is not already lower case would
    // project labels that no instance claims on the way back.
    EntityRef<Account> sponsorInstance = ACCOUNT.as("Sponsor");
    JoinRef<Membership, Account> sponsor =
        JoinRef.of(
            MEMBERSHIP.property("sponsorAccountId", Long.class),
            ACCOUNT.property("id", Long.class));

    FluentSelect<Membership> withSponsor =
        FluentSelect.from(MEMBERSHIP)
            .join(sponsor, sponsorInstance)
            .alsoSelect(sponsorInstance)
            .where(MEMBERSHIP.property("id", Long.class).is(10L));

    StepVerifier.create(executor.all(withSponsor, row -> row.read(sponsorInstance)))
        .assertNext(account -> assertThat(account.id).isEqualTo(2L))
        .verifyComplete();
  }

  @Test
  void aReservedWordEntityNameAndAMixedCaseColumnBothSurviveTheRoundTrip() {
    EntityRef<Order> order = EntityRef.of(Order.class);

    // Unquoted, the alias "order" makes this unparseable and "placedBy" folds to a column that
    // does not exist — and the second only shows up on hydration, after the count has succeeded.
    StepVerifier.create(
            executor.all(
                FluentSelect.from(order)
                    .where(order.property("placedBy", String.class).is("someone"))))
        .assertNext(
            placed -> {
              assertThat(placed.id).isEqualTo(1L);
              assertThat(placed.placedBy).isEqualTo("someone");
            })
        .verifyComplete();

    StepVerifier.create(executor.count(FluentSelect.from(order))).expectNext(1L).verifyComplete();
  }

  @Test
  void aStatementTheDatabaseRejectsArrivesAsAnErrorSignal() {
    StepVerifier.create(executor.count(FluentSelect.from(GHOST)))
        .expectError()
        .verify();
  }

  @Test
  void cancellingBeforeTheAnswerArrivesTerminatesWithoutCompleting() {
    StepVerifier.create(executor.count(FluentSelect.from(ACCOUNT)))
        .thenCancel()
        .verify();
  }
}
