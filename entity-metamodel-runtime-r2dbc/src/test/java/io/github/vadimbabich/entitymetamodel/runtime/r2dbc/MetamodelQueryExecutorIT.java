package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.ACCOUNT;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.MEMBERSHIP;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.SPONSOR;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.accountId;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.membershipAccountId;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.membershipId;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.memberships;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.ownerEmail;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.owningAccount;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.sponsoringAccount;
import static io.r2dbc.spi.ConnectionFactoryOptions.DATABASE;
import static io.r2dbc.spi.ConnectionFactoryOptions.DRIVER;
import static io.r2dbc.spi.ConnectionFactoryOptions.HOST;
import static io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD;
import static io.r2dbc.spi.ConnectionFactoryOptions.PORT;
import static io.r2dbc.spi.ConnectionFactoryOptions.USER;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.ExpressionSort;
import io.github.vadimbabich.entitymetamodel.runtime.JoinRef;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import io.github.vadimbabich.entitymetamodel.runtime.SqlExpr;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.AccountState;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.DocumentRecord;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.DottedSchemaDocument;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Ghost;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Membership;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.NotificationPreferenceSnapshot;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Order;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.SchemaDocument;
import io.r2dbc.proxy.ProxyConnectionFactory;
import io.r2dbc.proxy.core.QueryExecutionInfo;
import io.r2dbc.proxy.core.QueryInfo;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
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
@Execution(ExecutionMode.SAME_THREAD)
class MetamodelQueryExecutorIT {

  // Skips without Docker so the pre-commit gate reports a skip rather than a red build. Never in
  // CI: there a missing daemon is a broken runner, and passing silently would retire this layer.
  static boolean dockerIsAvailableOrRequired() {
    return DockerClientFactory.instance().isDockerAvailable() || System.getenv("CI") != null;
  }

  private static final EntityRef<Ghost> GHOST = EntityRef.of(Ghost.class);
  private static final EntityRef<DocumentRecord> DOCUMENT = EntityRef.of(DocumentRecord.class);

  private static final String INJECTION_ATTEMPT = "x' OR '1'='1";

  private static PostgreSQLContainer postgres;
  private static ConnectionFactory connectionFactory;
  private static DatabaseClient databaseClient;
  private static MetamodelQueryExecutor executor;

  // A second executor over the same database, through a connection factory that records what it
  // executes. Separate rather than wrapping the one above, because the proxy is not transparent: it
  // rewraps whatever bind() throws, which would change the fault the tests below assert on.
  private static MetamodelQueryExecutor recordingExecutor;
  private static final ExecutedStatements statements = new ExecutedStatements();
  private static CriteriaAdapter criteriaAdapter;

  @BeforeAll
  static void startDatabase() {
    postgres = new PostgreSQLContainer("postgres:16-alpine");
    postgres.start();

    connectionFactory = connectionFactoryFor(postgres);
    databaseClient = DatabaseClient.create(connectionFactory);

    // One context for all three: the renderer names the columns, the converter reads them back, and
    // the adapter resolves names against them.
    R2dbcMappingContext mappingContext = new R2dbcMappingContext();
    criteriaAdapter = new CriteriaAdapter(mappingContext);

    QueryRenderer renderer = TestRenderers.postgres(mappingContext);
    MappingR2dbcConverter converter = new MappingR2dbcConverter(mappingContext);

    executor = new MetamodelQueryExecutor(databaseClient, renderer, converter);
    recordingExecutor =
        new MetamodelQueryExecutor(
            DatabaseClient.create(statements.recording(connectionFactory)), renderer, converter);

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

    // A jsonb column the entity deliberately does not map, so a raw fragment can name it while the
    // projection stays one column wide.
    execute("create table document_records (id bigint primary key, attributes jsonb)");
    execute("insert into document_records values (1, '{\"archived\": true, \"kind\": \"note\"}')");
    execute("insert into document_records values (2, '{\"kind\": \"invoice\"}')");

    // A reserved-word entity name and a mixed-case column: the two identifier hazards every other
    // fixture name happens to avoid.
    execute("create table orders (id bigint primary key, \"placedBy\" varchar(255))");
    execute("insert into orders values (1, 'someone')");

    // A table in a non-default schema, which no other fixture has.
    execute("create schema app_schema");
    execute(
        "create table app_schema.v_document_meta (dm_doc_id bigint primary key,"
            + " dm_name varchar(255))");
    execute("insert into app_schema.v_document_meta values (1, 'first')");
    execute("insert into app_schema.v_document_meta values (2, 'second')");
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

  @Test
  void countReportsEveryRowWhenNothingIsFiltered() {
    StepVerifier.create(executor.count(FluentSelect.from(ACCOUNT)))
        .expectNext(3L)
        .verifyComplete();
  }

  @Test
  void aSchemaQualifiedEntityResolvesAgainstItsNonDefaultSchema() {
    assertReachesTheDocumentRelation(
        EntityRef.of(SchemaDocument.class), document -> document.name);
  }

  // Shared so the two spellings are held to the same rows and the same total, rather than drifting
  // into asserting different things about the relation they are supposed to agree on.
  private <T> void assertReachesTheDocumentRelation(
      EntityRef<T> documents, Function<T, String> name) {

    FluentSelect<T> byId =
        FluentSelect.from(documents).orderBy(documents.property("id", Long.class).asc());

    StepVerifier.create(executor.all(byId).map(name))
        .expectNext("first", "second")
        .verifyComplete();

    StepVerifier.create(executor.count(FluentSelect.from(documents)))
        .expectNext(2L)
        .verifyComplete();
  }

  /**
   * page renders two statements from two passes, so a qualified table has two chances to come out
   * differently; joined too, since alias handling is where a two-part name would most likely bite.
   */
  @Test
  void aSchemaQualifiedEntityPagesAndJoinsAgainstItsOwnSchema() {
    EntityRef<SchemaDocument> documents = EntityRef.of(SchemaDocument.class);
    PropertyRef<SchemaDocument, Long> documentId = documents.property("id", Long.class);

    FluentSelect<SchemaDocument> byId = FluentSelect.from(documents).orderBy(documentId.asc());

    StepVerifier.create(executor.page(byId, PageRequest.of(0, 1)))
        .assertNext(
            page -> {
              assertThat(page.getTotalElements()).isEqualTo(2L);
              assertThat(page.getContent()).extracting(document -> document.name).containsExactly("first");
            })
        .verifyComplete();

    FluentSelect<SchemaDocument> joinedToAccounts =
        FluentSelect.from(documents)
            .join(ACCOUNT)
            .on(documentId.eq(accountId()))
            .orderBy(documentId.asc());

    StepVerifier.create(executor.count(joinedToAccounts)).expectNext(2L).verifyComplete();
  }

  /**
   * This library splits the dotted spelling, not the mapping context, so only executing it proves
   * the two halves name a relation the database resolves.
   */
  @Test
  void aDottedSpellingReachesTheSameRelationAsTheSchemaAttribute() {
    assertReachesTheDocumentRelation(
        EntityRef.of(DottedSchemaDocument.class), document -> document.name);
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
                        accountId().in(List.of(1L, 2L)))))
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
                    .where(SqlExpr.raw("lower(owner_email) = {0}", "first@example.com"))))
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
                    .where(accountId().in(List.of(1L, 2L)))
                    .orderBy(accountId().desc())))
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
                FluentSelect.from(ACCOUNT).orderBy(accountId().asc())))
        .assertNext(accounts -> assertThat(accounts).hasSize(3))
        .verifyComplete();
  }

  @Test
  void firstTakesTheLeadingRowAndIsEmptyWhenNothingMatches() {
    StepVerifier.create(
            executor.first(
                FluentSelect.from(ACCOUNT).orderBy(accountId().asc())))
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
    statements.mark();

    StepVerifier.create(
            recordingExecutor.page(
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

    // A full page cannot know what follows it, so this is the branch that still counts.
    assertACountWasIssued();
  }

  @Test
  void theLastPartialPageReportsNoSuccessorAndItsTotalWithoutCounting() {
    // A page that starts past the beginning and comes back short has seen the end of the result, so
    // its offset plus its size is the total.
    statements.mark();

    StepVerifier.create(
            recordingExecutor.page(FluentSelect.from(ACCOUNT), PageRequest.of(1, 2, Sort.by("id"))))
        .assertNext(
            page -> {
              assertThat(page.getContent()).extracting(account -> account.id).containsExactly(3L);
              assertThat(page.getTotalElements()).isEqualTo(3L);
              assertThat(page.hasNext()).isFalse();
            })
        .verifyComplete();

    assertOneStatementAndNoCount();
  }

  @Test
  void aFirstPageShorterThanItsRequestNeedsNoCountEither() {
    // The other elided branch: nothing was skipped and the page did not fill.
    statements.mark();

    StepVerifier.create(
            recordingExecutor.page(
                FluentSelect.from(ACCOUNT).where(ownerEmail().is("first@example.com")),
                PageRequest.of(0, 10)))
        .assertNext(
            page -> {
              assertThat(page.getContent()).hasSize(1);
              assertThat(page.getTotalElements()).isEqualTo(1L);
            })
        .verifyComplete();

    assertOneStatementAndNoCount();
  }

  @Test
  void aPageCountsOnlyWhatTheFilterKeeps() {
    // A full page leaves rows unaccounted for, so this one does count — and the count must apply
    // the same filter the page did.
    statements.mark();

    StepVerifier.create(
            recordingExecutor.page(
                FluentSelect.from(ACCOUNT).where(ownerEmail().is("first@example.com")),
                PageRequest.of(0, 1)))
        .assertNext(
            page -> {
              assertThat(page.getContent()).hasSize(1);
              assertThat(page.getTotalElements()).isEqualTo(1L);
            })
        .verifyComplete();

    assertACountWasIssued();
  }

  @Test
  void aPagePastTheEndIsEmptyButStillReportsTheTotal() {
    // An empty page cannot skip its count: coming back empty says the offset overshot, not by how
    // much.
    statements.mark();

    StepVerifier.create(
            recordingExecutor.page(FluentSelect.from(ACCOUNT), PageRequest.of(9, 2, Sort.by("id"))))
        .assertNext(
            page -> {
              assertThat(page.getContent()).isEmpty();
              assertThat(page.getTotalElements()).isEqualTo(3L);
            })
        .verifyComplete();

    assertACountWasIssued();
  }

  @Test
  void anUnpagedRequestReturnsEverythingWithoutASecondStatement() {
    statements.mark();

    StepVerifier.create(
            recordingExecutor.page(FluentSelect.from(ACCOUNT), Pageable.unpaged(Sort.by("id"))))
        .assertNext(
            page -> {
              assertThat(page.getContent()).extracting(account -> account.id)
                  .containsExactly(1L, 2L, 3L);
              assertThat(page.getTotalElements()).isEqualTo(3L);
            })
        .verifyComplete();

    assertOneStatementAndNoCount();
  }

  @Test
  void aSliceCarriesItsRowsAndReportsASuccessorWithoutCounting() {
    statements.mark();

    StepVerifier.create(
            recordingExecutor.slice(
                FluentSelect.from(ACCOUNT), PageRequest.of(0, 2, Sort.by("id"))))
        .assertNext(
            slice -> {
              assertThat(slice.getContent())
                  .extracting(account -> account.id)
                  .containsExactly(1L, 2L);
              assertThat(slice.hasNext()).isTrue();
            })
        .verifyComplete();

    // The successor was reported by the row past the page, not by a second statement.
    assertOneStatementAndNoCount();
  }

  @Test
  void aSliceWhoseMatchesExactlyFillThePageReportsNoSuccessor() {
    // Three rows into a page of three is the last page; three rows into a page of two is not.
    StepVerifier.create(
            executor.slice(FluentSelect.from(ACCOUNT), PageRequest.of(0, 3, Sort.by("id"))))
        .assertNext(
            slice -> {
              assertThat(slice.getContent())
                  .extracting(account -> account.id)
                  .containsExactly(1L, 2L, 3L);
              assertThat(slice.hasNext()).isFalse();
            })
        .verifyComplete();
  }

  @Test
  void aSliceMapsThePageItReturnsAndNotTheProbeRow() {
    AtomicInteger mapperCalls = new AtomicInteger();

    StepVerifier.create(
            executor.slice(
                FluentSelect.from(ACCOUNT),
                PageRequest.of(0, 2, Sort.by("id")),
                row -> {
                  mapperCalls.incrementAndGet();
                  return row.read(ACCOUNT).ownerEmail;
                }))
        .assertNext(
            slice ->
                assertThat(slice.getContent())
                    .containsExactly("first@example.com", "second@example.com"))
        .verifyComplete();

    // Three rows were fetched and two returned, and the mapper saw only those two: one that counts,
    // caches or audits would otherwise drift by one on every slice with a successor.
    assertThat(mapperCalls).hasValue(2);
  }

  @Test
  void aSliceDoesNotMapTheProbeRowSoAnAbsentOuterJoinPastThePageCannotFailIt() {
    // Membership 13's sponsor does not exist, so read(SPONSOR) throws on the fourth row — which a
    // slice of three fetches as its probe and a page of three never sees.
    FluentSelect<Membership> sponsored =
        FluentSelect.from(MEMBERSHIP)
            .leftOuterJoin(sponsoringAccount(), SPONSOR)
            .alsoSelect(SPONSOR);

    StepVerifier.create(
            executor.slice(
                sponsored,
                PageRequest.of(0, 3, Sort.by("id")),
                row -> row.read(SPONSOR).id))
        .assertNext(
            slice -> {
              assertThat(slice.getContent()).containsExactly(2L, 2L, 1L);
              assertThat(slice.hasNext()).isTrue();
            })
        .verifyComplete();
  }

  @Test
  void aSliceOfTheLargestLegalPageReturnsEverythingAndReportsNoSuccessor() {
    // PageRequest.of(0, MAX_VALUE) is paged, so it carries a probe row the result set never
    // reaches.
    StepVerifier.create(
            executor.slice(FluentSelect.from(ACCOUNT), PageRequest.of(0, Integer.MAX_VALUE)))
        .assertNext(
            slice -> {
              assertThat(slice.getContent()).hasSize(3);
              assertThat(slice.hasNext()).isFalse();
            })
        .verifyComplete();
  }

  @Test
  void resubscribingToASliceAnswersTheSameThingTwice() {
    // The row counter has to belong to the subscription rather than the description: one shared
    // across subscriptions would let the second run start past its own page.
    Mono<Slice<Account>> firstPage =
        executor.slice(FluentSelect.from(ACCOUNT), PageRequest.of(0, 2, Sort.by("id")));

    for (int subscription = 0; subscription < 2; subscription++) {
      StepVerifier.create(firstPage)
          .assertNext(
              slice -> {
                assertThat(slice.getContent())
                    .extracting(account -> account.id)
                    .containsExactly(1L, 2L);
                assertThat(slice.hasNext()).isTrue();
              })
          .verifyComplete();
    }
  }

  @Test
  void aSlicePastTheEndIsEmptyAndReportsNoSuccessor() {
    StepVerifier.create(
            executor.slice(FluentSelect.from(ACCOUNT), PageRequest.of(9, 2, Sort.by("id"))))
        .assertNext(
            slice -> {
              assertThat(slice.getContent()).isEmpty();
              assertThat(slice.hasNext()).isFalse();
            })
        .verifyComplete();
  }

  @Test
  void anUnpagedSliceOverABoundedDescriptionReportsNoSuccessorThoughRowsWereLeftBehind() {
    // Decided, not incidental: a slice has no total to protect and an unpaged request has no next
    // page to ask for, so no successor is the only answer it can express.
    StepVerifier.create(
            executor.slice(FluentSelect.from(ACCOUNT).limit(2), Pageable.unpaged(Sort.by("id"))))
        .assertNext(
            slice -> {
              assertThat(slice.getContent())
                  .extracting(account -> account.id)
                  .containsExactly(1L, 2L);
              assertThat(slice.hasNext()).isFalse();
            })
        .verifyComplete();
  }

  @Test
  void anUnpagedSliceReturnsEverythingInOneSliceWithoutLimitingOrCounting() {
    statements.mark();

    StepVerifier.create(
            recordingExecutor.slice(FluentSelect.from(ACCOUNT), Pageable.unpaged(Sort.by("id"))))
        .assertNext(
            slice -> {
              assertThat(slice.getContent())
                  .extracting(account -> account.id)
                  .containsExactly(1L, 2L, 3L);
              assertThat(slice.hasNext()).isFalse();
            })
        .verifyComplete();

    assertOneStatementAndNoCount();
    assertThat(statements.sinceMark()).noneMatch(sql -> sql.contains("LIMIT"));
  }

  @Test
  void aSliceOfAMultipliedDescriptionCarriesDuplicatesJustAsItsPageDoes() {
    FluentSelect<Account> perMembership = FluentSelect.from(ACCOUNT).join(memberships());

    StepVerifier.create(executor.slice(perMembership, PageRequest.of(0, 2, Sort.by("id"))))
        .assertNext(
            slice -> {
              assertThat(slice.getContent())
                  .extracting(account -> account.id)
                  .containsExactly(1L, 1L);
              assertThat(slice.hasNext()).isTrue();
            })
        .verifyComplete();
  }

  @Test
  void aDistinctSliceCountsItsProbeRowInDistinctRowsToo() {
    // Three joined rows, two distinct accounts. The row fetched beyond the page has to be distinct
    // as well.
    FluentSelect<Account> distinctAccounts =
        FluentSelect.from(ACCOUNT).join(memberships()).distinct();

    StepVerifier.create(executor.slice(distinctAccounts, PageRequest.of(0, 1, Sort.by("id"))))
        .assertNext(
            slice -> {
              assertThat(slice.getContent()).extracting(account -> account.id).containsExactly(1L);
              assertThat(slice.hasNext()).isTrue();
            })
        .verifyComplete();
  }

  @Test
  void aKeysetPredicateOnTheLeadingKeyAloneSkipsRowsThatShareIt() {
    // Deliberately the wrong recipe, kept because it is the failure the right one prevents and the
    // reason the README states a unique terminal key as a caller obligation: standing after (1,
    // 10), a predicate on accountId alone jumps silently past membership 11, which shares it.
    assertPageAfter(membershipAccountId().gt(1L), 12L);
  }

  @Test
  void theRowValueFormTakesTheOppositeOperatorToWalkAnAllDescendingSort() {
    // Descending by (accountId, membershipId), the sort still runs one direction throughout, so the
    // row-value form applies with < rather than >. Reaching for > is silent: the SQL is valid, the
    // plan still seeks, and the traversal re-serves the leading row for ever.
    assertLeadingRow(descendingByKey(), 13L);

    assertDescendingPageAfter(rowValueKeysetBefore(999L, 13L), 12L);
    assertDescendingPageAfter(rowValueKeysetBefore(2L, 12L), 11L);
    assertDescendingPageAfter(rowValueKeysetBefore(1L, 11L), 10L);
    assertDescendingPageAfter(rowValueKeysetBefore(1L, 10L));
  }

  @Test
  void bothDocumentedKeysetFormsWalkEveryRowOnceAcrossATiedLeadingKey() {
    // Memberships ordered by (accountId, membershipId): (1,10), (1,11), (2,12), (999,13).
    assertLeadingRow(ascendingByKey(), 10L);

    assertPageAfter(expandedKeysetAfter(1L, 10L), 11L);
    assertPageAfter(expandedKeysetAfter(1L, 11L), 12L);
    assertPageAfter(expandedKeysetAfter(2L, 12L), 13L);
    assertPageAfter(expandedKeysetAfter(999L, 13L));

    assertPageAfter(rowValueKeysetAfter(1L, 10L), 11L);
    assertPageAfter(rowValueKeysetAfter(1L, 11L), 12L);
    assertPageAfter(rowValueKeysetAfter(2L, 12L), 13L);
    assertPageAfter(rowValueKeysetAfter(999L, 13L));
  }

  // One keyset step: the leading page of the sort both recipes are written against, narrowed by
  // whichever cursor predicate is under test.
  private void assertPageAfter(Condition after, Long... expectedIds) {
    assertLeadingRow(ascendingByKey().where(after), expectedIds);
  }

  private void assertDescendingPageAfter(Condition before, Long... expectedIds) {
    assertLeadingRow(descendingByKey().where(before), expectedIds);
  }

  private static FluentSelect<Membership> ascendingByKey() {
    return FluentSelect.from(MEMBERSHIP)
        .orderBy(membershipAccountId().asc(), membershipId().asc())
        .limit(1);
  }

  private static FluentSelect<Membership> descendingByKey() {
    return FluentSelect.from(MEMBERSHIP)
        .orderBy(membershipAccountId().desc(), membershipId().desc())
        .limit(1);
  }

  private void assertLeadingRow(FluentSelect<Membership> onePage, Long... expectedIds) {
    StepVerifier.create(executor.list(onePage))
        .assertNext(
            memberships ->
                assertThat(memberships)
                    .extracting(membership -> membership.id)
                    .containsExactly(expectedIds))
        .verifyComplete();
  }

  private static Condition expandedKeysetAfter(long cursorAccountId, long cursorMembershipId) {
    return ProductionPatterns.expandedKeysetAfter(
        membershipAccountId(), cursorAccountId, membershipId(), cursorMembershipId);
  }

  private static Condition rowValueKeysetAfter(long cursorAccountId, long cursorMembershipId) {
    return ProductionPatterns.rowValueKeysetAfter(
        membershipAccountId(), cursorAccountId, membershipId(), cursorMembershipId);
  }

  private static Condition rowValueKeysetBefore(long cursorAccountId, long cursorMembershipId) {
    return ProductionPatterns.rowValueKeysetBefore(
        membershipAccountId(), cursorAccountId, membershipId(), cursorMembershipId);
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
            .orderBy(ExpressionSort.asc(SqlExpr.raw("length({0})", ownerEmail())));

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
                membershipAccountId()
                    .eq(accountId())
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
                    .orderBy(accountId().asc())))
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
  void anExternallyBuiltFilterScopedToAJoinedInstanceFiltersThatInstancesRows() {
    // Membership 12 is owned by account 2 but sponsored by account 1, so a filter resolved against
    // the owner would return it. Membership 13's sponsor does not exist, so the inner join drops
    // it.
    Condition sponsoredBySecond =
        criteriaAdapter
            .toCondition(Criteria.where("ownerEmail").is("second@example.com"), SPONSOR)
            .orElseThrow();

    FluentSelect<Membership> sponsoredMemberships =
        FluentSelect.from(MEMBERSHIP)
            .join(sponsoringAccount(), SPONSOR)
            .where(sponsoredBySecond)
            .orderBy(MEMBERSHIP.property("id", Long.class).asc());

    StepVerifier.create(executor.list(sponsoredMemberships))
        .assertNext(
            memberships ->
                assertThat(memberships)
                    .extracting(membership -> membership.id)
                    .containsExactly(10L, 11L))
        .verifyComplete();
  }

  @Test
  void anUnpagedRequestOverABoundedDescriptionStillCountsTheWholeSet() {
    // A description carrying its own limit leaves rows behind, so reporting the rows in hand as the
    // total would fabricate one.
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
        FluentSelect.from(ACCOUNT).orderBy(accountId().desc());

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
    // Filtering a parent by a child attribute is the usual reason to join and reads as a
    // restriction, so the multiplication is pinned here.
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
  void distinctCollapsesAMultipliedDescriptionToItsDistinctRows() {
    // The remedy for the multiplication the two tests above pin.
    FluentSelect<Account> distinctAccounts =
        FluentSelect.from(ACCOUNT)
            .join(memberships())
            .distinct()
            .orderBy(accountId().asc());

    StepVerifier.create(executor.list(distinctAccounts))
        .assertNext(
            accounts ->
                assertThat(accounts).extracting(account -> account.id).containsExactly(1L, 2L))
        .verifyComplete();

    StepVerifier.create(executor.count(distinctAccounts)).expectNext(2L).verifyComplete();
  }

  @Test
  void aDistinctPageHoldsEachEntityOnceAndItsTotalAgrees() {
    FluentSelect<Account> distinctAccounts =
        FluentSelect.from(ACCOUNT).join(memberships()).distinct();

    StepVerifier.create(executor.page(distinctAccounts, PageRequest.of(0, 2, Sort.by("id"))))
        .assertNext(
            page -> {
              assertThat(page.getContent())
                  .extracting(account -> account.id)
                  .containsExactly(1L, 2L);
              assertThat(page.getTotalElements()).isEqualTo(2L);
              assertThat(page.hasNext()).isFalse();
            })
        .verifyComplete();
  }

  @Test
  void aDistinctProbePastAnOffsetSkipsDistinctRowsRatherThanJoinedOnes() {
    // Three joined rows but two distinct accounts. Both offsets matter: without the first a probe
    // whose DISTINCT collapsed a literal still passes, without the second joined rows pass as
    // distinct ones.
    FluentSelect<Account> perMembership = FluentSelect.from(ACCOUNT).join(memberships());

    StepVerifier.create(executor.exists(perMembership.offset(2)))
        .expectNext(Boolean.TRUE)
        .verifyComplete();

    StepVerifier.create(executor.exists(perMembership.distinct().offset(1)))
        .expectNext(Boolean.TRUE)
        .verifyComplete();

    StepVerifier.create(executor.exists(perMembership.distinct().offset(2)))
        .expectNext(Boolean.FALSE)
        .verifyComplete();
  }

  @Test
  void aCorrelatedExistsFragmentRestrictsWithoutMultiplyingOrDeduplicating() {
    // The third remedy: nothing joins, so the count is entity-shaped without a derived table.
    FluentSelect<Account> withMemberships =
        FluentSelect.from(ACCOUNT)
            .where(
                SqlExpr.raw(
                    "EXISTS (SELECT 1 FROM memberships m WHERE m.account_id = {0})",
                    accountId()))
            .orderBy(accountId().asc());

    StepVerifier.create(executor.list(withMemberships))
        .assertNext(
            accounts ->
                assertThat(accounts).extracting(account -> account.id).containsExactly(1L, 2L))
        .verifyComplete();

    StepVerifier.create(executor.count(withMemberships)).expectNext(2L).verifyComplete();
  }

  /**
   * PostgreSQL spells jsonb key existence with question marks, which the template grammar leaves as
   * literal text. {@code ?|} takes {@code text[]}, so its argument needs the {@code (Object)} cast
   * that keeps varargs from spreading the array.
   */
  @Test
  void aJsonbKeyExistenceOperatorExecutesWithItsArrayBound() {
    String[] eitherKey = {"archived", "missing"};

    FluentSelect<DocumentRecord> carryingEitherKey =
        FluentSelect.from(DOCUMENT)
            .where(SqlExpr.raw("attributes ?| {0}", (Object) eitherKey))
            .orderBy(DOCUMENT.property("id", Long.class).asc());

    StepVerifier.create(executor.list(carryingEitherKey))
        .assertNext(records -> assertThat(records).extracting(record -> record.id).containsExactly(1L))
        .verifyComplete();
  }

  /**
   * The containment half. A bound parameter arrives typed as text where a literal would be resolved
   * to jsonb by context, so without {@code ::jsonb} the operator has no candidate.
   */
  @Test
  void aJsonbContainmentOperatorExecutesWhenTheBoundParameterIsCast() {
    FluentSelect<DocumentRecord> invoices =
        FluentSelect.from(DOCUMENT)
            .where(SqlExpr.raw("attributes @> {0}::jsonb", "{\"kind\": \"invoice\"}"));

    StepVerifier.create(executor.list(invoices))
        .assertNext(records -> assertThat(records).extracting(record -> record.id).containsExactly(2L))
        .verifyComplete();
  }

  @Test
  void aBareQuestionMarkOperatorNeedsNoArgumentAtAll() {
    FluentSelect<DocumentRecord> archived =
        FluentSelect.from(DOCUMENT).where(SqlExpr.raw("attributes ? 'archived'"));

    StepVerifier.create(executor.count(archived)).expectNext(1L).verifyComplete();
  }

  @Test
  void aBindingFaultIsReportedAsOneWhateverTerminalMeetsIt() {
    FluentSelect<Account> markerInsideALiteral =
        FluentSelect.from(ACCOUNT).where(SqlExpr.raw("owner_email = '{0}'", "first@example.com"));

    StepVerifier.create(executor.list(markerInsideALiteral))
        .verifyErrorSatisfies(
            fault -> assertThat(fault).hasMessageContaining("Binding index 0"));

    StepVerifier.create(executor.count(markerInsideALiteral))
        .verifyErrorSatisfies(
            fault -> assertThat(fault).hasMessageContaining("Binding index 0"));
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
                    Criteria.where("id").is(1L).or("id").is(2L))),

            // Shapes the rest of the matrix does not reach; each separates the two foldings.
            Criteria.where("id").between(1L, 2L).or("id").is(3L),
            Criteria.where("id")
                .between(1L, 2L)
                .or("id")
                .is(3L)
                .and("ownerEmail")
                .is("first@example.com"),
            Criteria.empty()
                .and(Criteria.where("id").between(1L, 2L).or("id").is(3L))
                .and("ownerEmail")
                .is("first@example.com"),
            Criteria.where("ownerEmail")
                .notIn(List.of("first@example.com"))
                .and("id")
                .lessThanOrEquals(2L));

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

  /**
   * The same three members, chained and grouped, do not mean the same thing, and both readings
   * arrive from the filter layer. Parity alone would be satisfied by two sides folding a chain the
   * same wrong way; these row sets separate the readings.
   */
  @Test
  void aNarrowingAndAppliesToAGroupedRangeButNotToAChainedOne() {
    CriteriaDefinition asChain =
        Criteria.where("id").between(1L, 2L).or("id").is(3L).and("ownerEmail").is("first@example.com");

    CriteriaDefinition asGroup =
        Criteria.empty()
            .and(Criteria.where("id").between(1L, 2L).or("id").is(3L))
            .and("ownerEmail")
            .is("first@example.com");

    StepVerifier.create(accountIdsMatching(asChain)).expectNext(List.of(1L, 2L)).verifyComplete();
    StepVerifier.create(accountIdsMatching(asGroup)).expectNext(List.of(1L)).verifyComplete();
  }

  private Mono<List<Long>> accountIdsMatching(CriteriaDefinition filter) {
    return executor
        .all(
            FluentSelect.from(ACCOUNT)
                .where(criteriaAdapter.toCondition(filter, ACCOUNT).orElseThrow()))
        .map(account -> account.id)
        .collectSortedList();
  }

  interface SponsorEmail {
    String getOwnerEmail();
  }

  @Test
  void aJoinedInstanceReadsBackAsAProjectionOfItsOwnColumns() {
    FluentSelect<Membership> withSponsor =
        FluentSelect.from(MEMBERSHIP)
            .join(sponsoringAccount(), SPONSOR)
            .alsoSelect(SPONSOR)
            .where(MEMBERSHIP.property("id", Long.class).is(10L));

    StepVerifier.create(
            executor.all(
                withSponsor, row -> row.readProjection(SPONSOR, SponsorEmail.class)))
        .assertNext(sponsor -> assertThat(sponsor.getOwnerEmail()).isEqualTo("second@example.com"))
        .verifyComplete();
  }

  /**
   * The shape the projection door exists for. Assembling it by hand means reproducing the
   * sequential content-then-count rule, which fails only inside a transaction.
   */
  @Test
  void aPageCanCarryAProjectionOfAJoinedInstanceWithItsTotal() {
    FluentSelect<Membership> sponsored =
        FluentSelect.from(MEMBERSHIP)
            .join(sponsoringAccount(), SPONSOR)
            .alsoSelect(SPONSOR);

    StepVerifier.create(
            executor.page(
                sponsored,
                PageRequest.of(0, 2, Sort.by("id")),
                row -> row.readProjection(SPONSOR, SponsorEmail.class).getOwnerEmail()))
        .assertNext(
            page -> {
              assertThat(page.getContent())
                  .containsExactly("second@example.com", "second@example.com");
              assertThat(page.getTotalElements()).isEqualTo(3L);
              assertThat(page.hasNext()).isTrue();
            })
        .verifyComplete();
  }

  /** The two halves of this rule live in different classes, and it reads as if it deduplicates. */
  @Test
  void distinctCollapsesProjectedRowsRatherThanWhatAProjectionNarrowsThemTo() {
    FluentSelect<Membership> distinctRows =
        FluentSelect.from(MEMBERSHIP)
            .join(sponsoringAccount(), SPONSOR)
            .alsoSelect(SPONSOR)
            .distinct()
            .orderBy(MEMBERSHIP.property("id", Long.class).asc());

    StepVerifier.create(
            executor.all(
                distinctRows,
                row -> row.readProjection(SPONSOR, SponsorEmail.class).getOwnerEmail()))
        .expectNext("second@example.com")
        .expectNext("second@example.com")
        .expectNext("first@example.com")
        .verifyComplete();
  }

  private record MembershipParties(Membership membership, Account owner, Account sponsor) {
  }

  @Test
  void oneRowYieldsTwoInstancesOfTheSameTableWithTheirOwnValues() {
    JoinRef<Membership, Account> owner = owningAccount();
    JoinRef<Membership, Account> sponsor = sponsoringAccount();

    FluentSelect<Membership> parties =
        FluentSelect.from(MEMBERSHIP)
            .join(owner)
            .join(sponsor, SPONSOR)
            .alsoSelect(ACCOUNT)
            .alsoSelect(SPONSOR)
            .where(MEMBERSHIP.property("id", Long.class).is(10L));

    StepVerifier.create(
            executor.all(
                parties,
                row ->
                    new MembershipParties(
                        row.read(MEMBERSHIP), row.read(ACCOUNT), row.read(SPONSOR))))
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
    EntityRef<Account> mixedCaseSponsor = ACCOUNT.as("Sponsor");
    JoinRef<Membership, Account> sponsor = sponsoringAccount();

    FluentSelect<Membership> withSponsor =
        FluentSelect.from(MEMBERSHIP)
            .join(sponsor, mixedCaseSponsor)
            .alsoSelect(mixedCaseSponsor)
            .where(MEMBERSHIP.property("id", Long.class).is(10L));

    StepVerifier.create(executor.all(withSponsor, row -> row.read(mixedCaseSponsor)))
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

  @Test
  void cancellingAPageOrASliceBeforeItArrivesTerminatesWithoutCompleting() {
    // Both collect their rows before they can answer, so a cancel lands mid-fetch and must release
    // the connection rather than strand it.
    StepVerifier.create(executor.page(FluentSelect.from(ACCOUNT), PageRequest.of(0, 2)))
        .thenCancel()
        .verify();
    StepVerifier.create(executor.slice(FluentSelect.from(ACCOUNT), PageRequest.of(0, 2)))
        .thenCancel()
        .verify();
    StepVerifier.create(
            executor.slice(
                FluentSelect.from(ACCOUNT), PageRequest.of(0, 2), row -> row.read(ACCOUNT).id))
        .thenCancel()
        .verify();
  }

  @Test
  void aSliceOverAStatementTheDatabaseRejectsArrivesAsAnErrorSignal() {
    StepVerifier.create(executor.slice(FluentSelect.from(GHOST), PageRequest.of(0, 2)))
        .expectError()
        .verify();
    StepVerifier.create(
            executor.slice(FluentSelect.from(GHOST), PageRequest.of(0, 2), row -> row.read(GHOST)))
        .expectError()
        .verify();
  }

  // The negative assertion is the whole value: one statement and no count is what an elided count
  // looks like, and nothing else in the result distinguishes it from a count that ran.
  private static void assertOneStatementAndNoCount() {
    assertThat(statements.sinceMark()).hasSize(1).noneMatch(MetamodelQueryExecutorIT::isACount);
  }

  private static void assertACountWasIssued() {
    assertThat(statements.sinceMark()).filteredOn(MetamodelQueryExecutorIT::isACount).hasSize(1);
  }

  private static boolean isACount(String sql) {
    return sql.startsWith("SELECT COUNT(");
  }

  /**
   * The statements the driver actually executed, so a test can assert what was <em>not</em> issued.
   *
   * <p>Wrapped with the R2DBC project's own listener SPI rather than a hand-written decorator:
   * {@code io.r2dbc.spi.Connection} alone declares eighteen methods that would otherwise need plain
   * delegation here.
   */
  private static final class ExecutedStatements {

    // Appended from the driver's event-loop thread and read from the test thread.
    private final List<String> executed = new CopyOnWriteArrayList<>();

    ConnectionFactory recording(ConnectionFactory target) {
      // onBeforeQuery, not onAfterQuery: a negative assertion must not depend on the callback
      // arriving before the terminal signal StepVerifier waits for.
      return ProxyConnectionFactory.builder(target).onBeforeQuery(this::record).build();
    }

    // Since a mark rather than since JVM start: one listener serves every method here, so "no count
    // was recorded" over a global list would pass or fail on what ran before. The mark is global,
    // so it is only sound while this class runs on one thread — which is what the class-level
    // SAME_THREAD declaration keeps true if parallel execution is ever switched on.
    void mark() {
      executed.clear();
    }

    List<String> sinceMark() {
      return List.copyOf(executed);
    }

    private void record(QueryExecutionInfo execution) {
      for (QueryInfo query : execution.getQueries()) {
        executed.add(query.getQuery());
      }
    }
  }
}
