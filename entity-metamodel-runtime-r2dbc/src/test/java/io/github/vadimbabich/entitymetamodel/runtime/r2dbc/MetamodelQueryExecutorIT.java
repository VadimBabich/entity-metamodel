package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static io.r2dbc.spi.ConnectionFactoryOptions.DATABASE;
import static io.r2dbc.spi.ConnectionFactoryOptions.DRIVER;
import static io.r2dbc.spi.ConnectionFactoryOptions.HOST;
import static io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD;
import static io.r2dbc.spi.ConnectionFactoryOptions.PORT;
import static io.r2dbc.spi.ConnectionFactoryOptions.USER;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.ExpressionSort;
import io.github.vadimbabich.entitymetamodel.runtime.JoinRef;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import io.github.vadimbabich.entitymetamodel.runtime.SqlExpr;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.AccountState;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Ghost;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Membership;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.NotificationPreferenceSnapshot;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Order;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.CriteriaDefinition;
import org.springframework.data.relational.core.query.Query;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Execution against a real PostgreSQL. Rendering can be proved on paper; that the database accepts
 * the SQL, applies the binds and reports the counts we expect cannot be.
 */
@EnabledIf("dockerIsAvailableOrRequired")
class MetamodelQueryExecutorIT {

  // Skips without Docker so the pre-commit gate reports a skip rather than a red build. Never in
  // CI: there a missing daemon is a broken runner, and passing silently would retire this layer.
  static boolean dockerIsAvailableOrRequired() {
    return DockerClientFactory.instance().isDockerAvailable() || System.getenv("CI") != null;
  }

  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);
  private static final EntityRef<Membership> MEMBERSHIP = EntityRef.of(Membership.class);
  private static final EntityRef<Ghost> GHOST = EntityRef.of(Ghost.class);

  private static final String INJECTION_ATTEMPT = "x' OR '1'='1";

  private static PostgreSQLContainer postgres;
  private static ConnectionFactory connectionFactory;
  private static DatabaseClient databaseClient;
  private static MetamodelQueryExecutor executor;
  private static CriteriaAdapter criteriaAdapter;

  @BeforeAll
  static void startDatabase() {
    postgres = new PostgreSQLContainer("postgres:16-alpine");
    postgres.start();

    connectionFactory = connectionFactoryFor(postgres);
    databaseClient = DatabaseClient.create(connectionFactory);

    // One context for all three: the renderer names the columns, the converter reads them back, and
    // the adapter resolves names against them, so two contexts would project labels nothing claims.
    R2dbcMappingContext mappingContext = new R2dbcMappingContext();
    criteriaAdapter = new CriteriaAdapter(mappingContext);
    executor =
        new MetamodelQueryExecutor(
            databaseClient,
            new QueryRenderer(mappingContext, PostgresDialect.INSTANCE),
            new MappingR2dbcConverter(mappingContext));

    // block() in a fixture is the sanctioned exception: the schema is a precondition.
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
    // counterpart to miss on.
    execute("insert into memberships values (13, 999, 888)");

    // Labels long enough to pass the 63-byte limit, the only way to prove the fallback against a
    // real truncating database.
    execute(
        "create table notification_preference_snapshots (id bigint primary key,"
            + " last_updated_by_administrator_account_identifier varchar(255))");
    execute("insert into notification_preference_snapshots values (1, 'an-administrator')");

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

  // Bound rather than concatenated: one of these addresses is a quote-carrying injection attempt.
  private static void insertAccount(Long id, String email, AccountState state) {
    databaseClient.sql("insert into accounts values ($1, $2, $3)")
        .bind(0, id)
        .bind(1, email)
        .bind(2, state.name())
        .fetch()
        .rowsUpdated()
        .block();
  }

  // The three relationships this suite traverses, named once.
  private static JoinRef<Membership, Account> owningAccount() {
    return JoinRef.of(
        MEMBERSHIP.property("accountId", Long.class), ACCOUNT.property("id", Long.class));
  }

  private static JoinRef<Membership, Account> sponsoringAccount() {
    return JoinRef.of(
        MEMBERSHIP.property("sponsorAccountId", Long.class), ACCOUNT.property("id", Long.class));
  }

  // The reverse direction: one account's memberships, the shape that multiplies rows.
  private static JoinRef<Account, Membership> memberships() {
    return JoinRef.of(
        ACCOUNT.property("id", Long.class), MEMBERSHIP.property("accountId", Long.class));
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
  void aCountExcludesRowsWhoseJoinFindsNoCounterpart() {
    JoinRef<Membership, Account> account = owningAccount();

    StepVerifier.create(executor.count(FluentSelect.from(MEMBERSHIP).join(account)))
        .expectNext(3L)
        .verifyComplete();
  }

  @Test
  void aLeftOuterJoinKeepsParentsWithoutAMatch() {
    JoinRef<Membership, Account> sponsor = sponsoringAccount();

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
    JoinRef<Membership, Account> account = owningAccount();

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

  @Test
  void aPageCarriesItsRowsAndTheTotalBehindThem() {
    StepVerifier.create(
            executor.page(
                FluentSelect.from(ACCOUNT), PageRequest.of(0, 2, Sort.by("id"))))
        .assertNext(
            page -> {
              assertThat(page.getContent())
                  .extracting(account -> account.id)
                  .containsExactly(1L, 2L);
              assertThat(page.getTotalElements()).isEqualTo(3L);
              assertThat(page.hasNext()).isTrue();
            })
        .verifyComplete();
  }

  @Test
  void theLastPageReportsNoSuccessorAndKeepsTheSameTotal() {
    StepVerifier.create(
            executor.page(FluentSelect.from(ACCOUNT), PageRequest.of(1, 2, Sort.by("id"))))
        .assertNext(
            page -> {
              assertThat(page.getContent()).extracting(account -> account.id).containsExactly(3L);
              assertThat(page.getTotalElements()).isEqualTo(3L);
              assertThat(page.hasNext()).isFalse();
            })
        .verifyComplete();
  }

  @Test
  void aPageCountsOnlyWhatTheFilterKeeps() {
    StepVerifier.create(
            executor.page(
                FluentSelect.from(ACCOUNT).where(ownerEmail().is("first@example.com")),
                PageRequest.of(0, 10)))
        .assertNext(
            page -> {
              assertThat(page.getContent()).hasSize(1);
              assertThat(page.getTotalElements()).isEqualTo(1L);
            })
        .verifyComplete();
  }

  @Test
  void aPagePastTheEndIsEmptyButStillReportsTheTotal() {
    StepVerifier.create(
            executor.page(FluentSelect.from(ACCOUNT), PageRequest.of(9, 2, Sort.by("id"))))
        .assertNext(
            page -> {
              assertThat(page.getContent()).isEmpty();
              assertThat(page.getTotalElements()).isEqualTo(3L);
            })
        .verifyComplete();
  }

  @Test
  void anUnpagedRequestReturnsEverythingWithoutASecondStatement() {
    StepVerifier.create(
            executor.page(FluentSelect.from(ACCOUNT), Pageable.unpaged(Sort.by("id"))))
        .assertNext(
            page -> {
              assertThat(page.getContent()).extracting(account -> account.id)
                  .containsExactly(1L, 2L, 3L);
              assertThat(page.getTotalElements()).isEqualTo(3L);
            })
        .verifyComplete();
  }

  @Test
  void aSortArrivingAsTextOrdersByThatPropertysColumn() {
    StepVerifier.create(
            executor.page(
                FluentSelect.from(ACCOUNT),
                PageRequest.of(0, 3, Sort.by(Sort.Direction.DESC, "ownerEmail"))))
        .assertNext(
            page ->
                assertThat(page.getContent())
                    .extracting(account -> account.ownerEmail)
                    .containsExactly(INJECTION_ATTEMPT, "second@example.com", "first@example.com"))
        .verifyComplete();
  }

  @Test
  void anExpressionSortOrdersBySomethingNoPropertyNameCouldSay() {
    FluentSelect<Account> byAddressLength =
        FluentSelect.from(ACCOUNT)
            .orderBy(ExpressionSort.asc(SqlExpr.raw("length(?)", ownerEmail())));

    StepVerifier.create(executor.list(byAddressLength))
        .assertNext(
            accounts ->
                assertThat(accounts)
                    .extracting(account -> account.ownerEmail)
                    .containsExactly(
                        INJECTION_ATTEMPT, "first@example.com", "second@example.com"))
        .verifyComplete();
  }

  @Test
  void anInstanceJoinedOnAStatedConditionFiltersTheSameWayADeclaredOneDoes() {
    FluentSelect<Membership> ownedByAnActiveAccount =
        FluentSelect.from(MEMBERSHIP)
            .join(ACCOUNT)
            .on(
                MEMBERSHIP.property("accountId", Long.class)
                    .eq(ACCOUNT.property("id", Long.class))
                    .and(ownerEmail().is("first@example.com")));

    StepVerifier.create(executor.list(ownedByAnActiveAccount))
        .assertNext(
            memberships ->
                assertThat(memberships).extracting(membership -> membership.id)
                    .containsExactly(10L, 11L))
        .verifyComplete();
  }

  @Test
  void aNegatedFilterKeepsExactlyTheRowsTheFilterExcluded() {
    StepVerifier.create(
            executor.list(
                FluentSelect.from(ACCOUNT)
                    .where(ownerEmail().is("first@example.com").not())
                    .orderBy(ACCOUNT.property("id", Long.class).asc())))
        .assertNext(
            accounts ->
                assertThat(accounts).extracting(account -> account.id).containsExactly(2L, 3L))
        .verifyComplete();
  }

  @Test
  void anEntityWhoseLabelsWouldBeTruncatedStillHydratesFromItsRenamedColumns() {
    EntityRef<NotificationPreferenceSnapshot> snapshot =
        EntityRef.of(NotificationPreferenceSnapshot.class);

    StepVerifier.create(executor.all(FluentSelect.from(snapshot)))
        .assertNext(
            hydrated -> {
              assertThat(hydrated.id).isEqualTo(1L);
              assertThat(hydrated.lastUpdatedBy).isEqualTo("an-administrator");
            })
        .verifyComplete();
  }

  @Test
  void anExternallyBuiltFilterNarrowsTheQueryTheSameWayATypedOneDoes() {
    Condition fromTheFilterLayer =
        criteriaAdapter
            .toCondition(
                Criteria.where("state").is(AccountState.ACTIVE.name()).and("id").greaterThan(1L),
                ACCOUNT)
            .orElseThrow();

    StepVerifier.create(executor.list(FluentSelect.from(ACCOUNT).where(fromTheFilterLayer)))
        .assertNext(
            accounts ->
                assertThat(accounts).extracting(account -> account.id).containsExactly(2L))
        .verifyComplete();
  }

  @Test
  void anUnpagedRequestOverABoundedDescriptionStillCountsTheWholeSet() {
    // The rows in hand are the total only when nothing was left behind. A description carrying its
    // own limit leaves rows behind, so reporting its size as the total would fabricate one.
    StepVerifier.create(
            executor.page(FluentSelect.from(ACCOUNT).limit(2), Pageable.unpaged(Sort.by("id"))))
        .assertNext(
            page -> {
              assertThat(page.getContent()).hasSize(2);
              assertThat(page.getTotalElements()).isEqualTo(3L);
            })
        .verifyComplete();
  }

  @Test
  void aScopedFilterStaysScopedWhenItsSearchTermIsADisjunction() {
    // The composition every filter layer writes: a scoping predicate plus a search whose chain ends
    // in OR. Take the member's combinator and the scope stops constraining.
    CriteriaDefinition scoped =
        CriteriaDefinition.from(
            List.of(
                Criteria.where("id").is(1L),
                Criteria.where("ownerEmail").like("%example.com").or("state").is("CLOSED")));

    StepVerifier.create(
            executor.list(
                FluentSelect.from(ACCOUNT)
                    .where(criteriaAdapter.toCondition(scoped, ACCOUNT).orElseThrow())))
        .assertNext(
            accounts ->
                assertThat(accounts).extracting(account -> account.id).containsExactly(1L))
        .verifyComplete();
  }

  @Test
  void aRequestedSortLeadsTheDescriptionsOwn() {
    FluentSelect<Account> byIdDescending =
        FluentSelect.from(ACCOUNT).orderBy(ACCOUNT.property("id", Long.class).desc());

    StepVerifier.create(executor.page(byIdDescending, PageRequest.of(0, 3, Sort.by("ownerEmail"))))
        .assertNext(
            page ->
                assertThat(page.getContent())
                    .extracting(account -> account.ownerEmail)
                    .containsExactly("first@example.com", "second@example.com", INJECTION_ATTEMPT))
        .verifyComplete();
  }

  @Test
  void aClearedFilterReturnsNoRowsRatherThanFailing() {
    Condition nothingSelected =
        criteriaAdapter.toCondition(Criteria.where("id").in(List.of()), ACCOUNT).orElseThrow();

    StepVerifier.create(executor.list(FluentSelect.from(ACCOUNT).where(nothingSelected)))
        .assertNext(accounts -> assertThat(accounts).isEmpty())
        .verifyComplete();
  }

  @Test
  void excludingNothingKeepsEveryRow() {
    Condition nothingExcluded =
        criteriaAdapter.toCondition(Criteria.where("id").notIn(List.of()), ACCOUNT).orElseThrow();

    StepVerifier.create(executor.count(FluentSelect.from(ACCOUNT).where(nothingExcluded)))
        .expectNext(3L)
        .verifyComplete();
  }

  @Test
  void aJoinToTheManySideMultipliesRowsAndEveryTerminalCountsThem() {
    // The direction that expands. Filtering a parent by a child attribute is the usual reason to
    // join and reads as a restriction, so the multiplication is pinned here.
    JoinRef<Account, Membership> memberships = memberships();
    FluentSelect<Account> perMembership = FluentSelect.from(ACCOUNT).join(memberships);

    StepVerifier.create(executor.list(perMembership))
        .assertNext(
            accounts ->
                assertThat(accounts).extracting(account -> account.id).containsExactly(1L, 1L, 2L))
        .verifyComplete();

    // Two accounts match; three rows do.
    StepVerifier.create(executor.count(perMembership)).expectNext(3L).verifyComplete();
  }

  @Test
  void aPageOfAMultipliedDescriptionCarriesDuplicatesAndCountsRows() {
    JoinRef<Account, Membership> memberships = memberships();
    FluentSelect<Account> perMembership = FluentSelect.from(ACCOUNT).join(memberships);

    StepVerifier.create(executor.page(perMembership, PageRequest.of(0, 2, Sort.by("id"))))
        .assertNext(
            page -> {
              assertThat(page.getContent())
                  .extracting(account -> account.id)
                  .containsExactly(1L, 1L);
              assertThat(page.getTotalElements()).isEqualTo(3L);
              assertThat(page.hasNext()).isTrue();
            })
        .verifyComplete();
  }

  @Test
  void moreThanOneMatchIsReportedAsSuchRatherThanAsAnIndexFault() {
    JoinRef<Account, Membership> memberships = memberships();

    StepVerifier.create(
            executor.one(
                FluentSelect.from(ACCOUNT)
                    .join(memberships)
                    .where(ownerEmail().is("first@example.com"))))
        .expectErrorSatisfies(
            tooMany ->
                assertThat(tooMany)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("more than one row"))
        .verify();
  }

  @Test
  void anUnpagedRequestOverAnOffsetDescriptionStillCountsTheWholeSet() {
    // An offset leaves rows behind exactly as a limit does, so the rows in hand are not the total.
    StepVerifier.create(
            executor.page(FluentSelect.from(ACCOUNT).offset(1), Pageable.unpaged(Sort.by("id"))))
        .assertNext(
            page -> {
              assertThat(page.getContent()).hasSize(2);
              assertThat(page.getTotalElements()).isEqualTo(3L);
            })
        .verifyComplete();
  }

  @Test
  void aDescriptionThatSelectsNoRowsNeitherExistsNorHasAFirst() {
    FluentSelect<Account> none = FluentSelect.from(ACCOUNT).limit(0);

    StepVerifier.create(executor.exists(none)).expectNext(Boolean.FALSE).verifyComplete();
    StepVerifier.create(executor.first(none)).verifyComplete();
  }

  /**
   * The promise the door is built on, measured rather than reasoned: chain precedence is too subtle
   * for that. The first shape distinguishes the two readings — {@code a OR (b AND c)} keeps account
   * 1, {@code (a OR b) AND c} keeps nothing.
   */
  @Test
  void aFilterReturnsTheSameRowsThroughThisAdapterAsThroughTheSubstratesOwnTemplate() {
    R2dbcEntityTemplate template = new R2dbcEntityTemplate(connectionFactory);

    // Columns both sides bind identically: a bare template converts a String to the property's enum
    // type and then cannot encode it, which is not what this comparison is about.
    List<CriteriaDefinition> shapes =
        List.of(
            Criteria.where("id").is(1L).or("ownerEmail").is("nope").and("id").is(2L),
            Criteria.where("id").is(1L).and("ownerEmail").is("first@example.com").or("id").is(3L),
            Criteria.where("id").is(1L).or("id").is(2L).or("id").is(3L),
            Criteria.where("ownerEmail").like("%example.com").and("id").greaterThan(1L),
            CriteriaDefinition.from(
                List.of(
                    Criteria.where("ownerEmail").like("%example.com"),
                    Criteria.where("id").is(1L).or("id").is(2L))));

    for (CriteriaDefinition shape : shapes) {
      Mono<List<Long>> throughSubstrate =
          template
              .select(Account.class)
              .matching(Query.query(shape))
              .all()
              .map(account -> account.id)
              .collectSortedList();

      Mono<List<Long>> throughAdapter =
          executor
              .all(
                  FluentSelect.from(ACCOUNT)
                      .where(criteriaAdapter.toCondition(shape, ACCOUNT).orElseThrow()))
              .map(account -> account.id)
              .collectSortedList();

      StepVerifier.create(throughSubstrate.zipWith(throughAdapter))
          .assertNext(
              rows ->
                  assertThat(rows.getT2())
                      .describedAs("%s", shape)
                      .isEqualTo(rows.getT1()))
          .verifyComplete();
    }
  }

  private record MembershipParties(Membership membership, Account owner, Account sponsor) {
  }

  @Test
  void oneRowYieldsTwoInstancesOfTheSameTableWithTheirOwnValues() {
    EntityRef<Account> sponsorInstance = ACCOUNT.as("sponsor");
    JoinRef<Membership, Account> owner = owningAccount();
    JoinRef<Membership, Account> sponsor = sponsoringAccount();

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
    JoinRef<Membership, Account> owner = owningAccount();

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
    JoinRef<Membership, Account> sponsor = sponsoringAccount();

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
