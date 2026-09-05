package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Person;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;

import java.util.function.Function;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Describing a query must cost nothing until something subscribes. Without Docker on purpose: a
 * connection factory that refuses connections proves that more sharply than a working database.
 */
class ExecutorAssemblyTest {

  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);
  private static final EntityRef<Person> PERSON = EntityRef.of(Person.class);
  private static final R2dbcMappingContext MAPPING_CONTEXT = new R2dbcMappingContext();

  private static final String NOT_A_PERSISTED_PROPERTY = "nickname";

  private static final Function<ProjectedRow, String> OWNER_EMAIL =
      row -> row.read(ACCOUNT).ownerEmail;

  private static final Function<ProjectedRow, Person> WHOLE_PERSON = row -> row.read(PERSON);

  private final RefusingConnectionFactory connectionFactory = new RefusingConnectionFactory();

  private final MetamodelQueryExecutor executor = executorFor(MAPPING_CONTEXT, connectionFactory);

  @Test
  void buildingATerminalTouchesNoConnection() {
    Mono<Long> unsubscribedCount = executor.count(FluentSelect.from(ACCOUNT));
    Mono<Boolean> unsubscribedExists = executor.exists(FluentSelect.from(ACCOUNT));

    assertThat(unsubscribedCount).isNotNull();
    assertThat(unsubscribedExists).isNotNull();
    assertThat(connectionFactory.connectionsRequested).isZero();
  }

  @Test
  void everyTerminalReturnsAPublisherRatherThanAResult() {
    Mono<Long> count = executor.count(FluentSelect.from(ACCOUNT));
    Mono<Boolean> exists = executor.exists(FluentSelect.from(ACCOUNT));

    assertThat(count).isInstanceOf(Publisher.class);
    assertThat(exists).isInstanceOf(Publisher.class);
  }

  @Test
  void callingATerminalTwiceDescribesTwoIndependentQueries() {
    FluentSelect<Account> everyAccount = FluentSelect.from(ACCOUNT);

    Mono<Long> first = executor.count(everyAccount);
    Mono<Long> second = executor.count(everyAccount);

    assertThat(first).isNotSameAs(second);
    assertThat(connectionFactory.connectionsRequested).isZero();
  }

  @Test
  void onlyTheTerminalsThatHydrateRefuseAnEmbeddedBearingEntity() {
    FluentSelect<Person> people = FluentSelect.from(PERSON);

    // The refusal exists because hydration would drop the embedded value silently; counting and
    // probing hydrate nothing.
    assertThatNoException().isThrownBy(() -> executor.count(people));
    assertThatNoException().isThrownBy(() -> executor.exists(people));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> executor.all(people))
        .withMessageContaining("embedded");

    // Taking a mapper is not what moves a terminal's failures into its publisher, so the mapper
    // forms refuse exactly as their entity forms do.
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> executor.one(people, WHOLE_PERSON))
        .withMessageContaining("embedded");
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> executor.first(people, WHOLE_PERSON))
        .withMessageContaining("embedded");
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> executor.list(people, WHOLE_PERSON))
        .withMessageContaining("embedded");

    // The terminals taking a Pageable hydrate as well, so they refuse too — but they take request
    // data, which puts the refusal in the publisher rather than in a throw.
    assertThatNoException().isThrownBy(() -> executor.page(people, PageRequest.of(0, 10)));
    assertThatNoException().isThrownBy(() -> executor.slice(people, PageRequest.of(0, 10)));
    assertThatNoException().isThrownBy(() -> executor.all(people, PageRequest.of(0, 10)));
  }

  @Test
  void pageReportsTheSameRefusalThroughThePublisherRatherThanByThrowing() {
    // The same description throws from all() and signals from page().
    FluentSelect<Person> people = FluentSelect.from(PERSON);

    StepVerifier.create(executor.page(people, PageRequest.of(0, 10)))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(executor.page(people, PageRequest.of(0, 10), WHOLE_PERSON))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void sliceReportsTheSameRefusalThroughThePublisherRatherThanByThrowing() {
    FluentSelect<Person> people = FluentSelect.from(PERSON);

    StepVerifier.create(executor.slice(people, PageRequest.of(0, 10)))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(executor.slice(people, PageRequest.of(0, 10), WHOLE_PERSON))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void aWindowedAllReportsTheSameRefusalThroughThePublisherRatherThanByThrowing() {
    FluentSelect<Person> people = FluentSelect.from(PERSON);

    StepVerifier.create(executor.all(people, PageRequest.of(0, 10)))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(executor.all(people, PageRequest.of(0, 10), WHOLE_PERSON))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void aPageIsAlsoJustADescriptionUntilSomethingSubscribes() {
    Mono<Page<Account>> unsubscribedPage =
        executor.page(FluentSelect.from(ACCOUNT), PageRequest.of(0, 20));

    assertThat(unsubscribedPage).isInstanceOf(Publisher.class);
    assertThat(connectionFactory.connectionsRequested).isZero();
  }

  @Test
  void aPageReportsABadSortPropertyThroughThePublisherRatherThanByThrowing() {
    // A page request's sort property is data, not a programmer's ref, so the reactive rules put it
    // in the publisher: a handler mapping it to 400 never sees a synchronous throw.
    Mono<Page<Account>> page =
        executor.page(
            FluentSelect.from(ACCOUNT),
            PageRequest.of(0, 10, Sort.by(NOT_A_PERSISTED_PROPERTY)));

    StepVerifier.create(page).expectError(IllegalArgumentException.class).verify();
    assertThat(connectionFactory.connectionsRequested).isZero();
  }

  @Test
  void aPageRejectsAMissingDescriptionOrRequest() {
    assertThatNullPointerException()
        .isThrownBy(() -> executor.page(null, Pageable.unpaged()));
    assertThatNullPointerException()
        .isThrownBy(() -> executor.page(FluentSelect.from(ACCOUNT), null));
  }

  @Test
  void aMappedPageIsAlsoJustADescriptionUntilSomethingSubscribes() {
    Mono<Page<String>> unsubscribedPage =
        executor.page(
            FluentSelect.from(ACCOUNT),
            PageRequest.of(0, 20),
            OWNER_EMAIL);

    assertThat(unsubscribedPage).isInstanceOf(Publisher.class);
    assertThat(connectionFactory.connectionsRequested).isZero();
  }

  @Test
  void aMappedPageReportsABadSortPropertyThroughThePublisherRatherThanByThrowing() {
    Mono<Page<String>> page =
        executor.page(
            FluentSelect.from(ACCOUNT),
            PageRequest.of(0, 10, Sort.by(NOT_A_PERSISTED_PROPERTY)),
            OWNER_EMAIL);

    StepVerifier.create(page).expectError(IllegalArgumentException.class).verify();
    assertThat(connectionFactory.connectionsRequested).isZero();
  }

  @Test
  void aMappedPageRejectsAMissingDescriptionRequestOrMapper() {
    assertThatNullPointerException()
        .isThrownBy(() -> executor.page(null, Pageable.unpaged(), row -> row.read(ACCOUNT)));
    assertThatNullPointerException()
        .isThrownBy(
            () -> executor.page(FluentSelect.from(ACCOUNT), null, row -> row.read(ACCOUNT)));
    assertThatNullPointerException()
        .isThrownBy(() -> executor.page(FluentSelect.from(ACCOUNT), PageRequest.of(0, 10), null));
  }

  @Test
  void aSliceIsAlsoJustADescriptionUntilSomethingSubscribes() {
    Mono<Slice<Account>> unsubscribedSlice =
        executor.slice(FluentSelect.from(ACCOUNT), PageRequest.of(0, 20));

    assertThat(unsubscribedSlice).isInstanceOf(Publisher.class);
    assertThat(connectionFactory.connectionsRequested).isZero();
  }

  @Test
  void aMappedSliceIsAlsoJustADescriptionUntilSomethingSubscribes() {
    Mono<Slice<String>> unsubscribedSlice =
        executor.slice(
            FluentSelect.from(ACCOUNT),
            PageRequest.of(0, 20),
            OWNER_EMAIL);

    assertThat(unsubscribedSlice).isInstanceOf(Publisher.class);
    assertThat(connectionFactory.connectionsRequested).isZero();
  }

  @Test
  void aSliceReportsABadSortPropertyThroughThePublisherRatherThanByThrowing() {
    Mono<Slice<Account>> slice =
        executor.slice(
            FluentSelect.from(ACCOUNT),
            PageRequest.of(0, 10, Sort.by(NOT_A_PERSISTED_PROPERTY)));

    StepVerifier.create(slice).expectError(IllegalArgumentException.class).verify();
    assertThat(connectionFactory.connectionsRequested).isZero();
  }

  @Test
  void aMappedSliceReportsABadSortPropertyThroughThePublisherRatherThanByThrowing() {
    Mono<Slice<String>> slice =
        executor.slice(
            FluentSelect.from(ACCOUNT),
            PageRequest.of(0, 10, Sort.by(NOT_A_PERSISTED_PROPERTY)),
            OWNER_EMAIL);

    StepVerifier.create(slice).expectError(IllegalArgumentException.class).verify();
    assertThat(connectionFactory.connectionsRequested).isZero();
  }

  @Test
  void aSliceRejectsAMissingDescriptionOrRequest() {
    assertThatNullPointerException().isThrownBy(() -> executor.slice(null, Pageable.unpaged()));
    assertThatNullPointerException()
        .isThrownBy(() -> executor.slice(FluentSelect.from(ACCOUNT), null));
  }

  @Test
  void aMappedSliceRejectsAMissingDescriptionRequestOrMapper() {
    assertThatNullPointerException()
        .isThrownBy(() -> executor.slice(null, Pageable.unpaged(), row -> row.read(ACCOUNT)));
    assertThatNullPointerException()
        .isThrownBy(
            () -> executor.slice(FluentSelect.from(ACCOUNT), null, row -> row.read(ACCOUNT)));
    assertThatNullPointerException()
        .isThrownBy(() -> executor.slice(FluentSelect.from(ACCOUNT), PageRequest.of(0, 10), null));
  }

  @Test
  void aWindowedAllIsAlsoJustADescriptionUntilSomethingSubscribes() {
    Flux<Account> unsubscribedWindow =
        executor.all(FluentSelect.from(ACCOUNT), PageRequest.of(0, 20));

    assertThat(unsubscribedWindow).isInstanceOf(Publisher.class);
    assertThat(connectionFactory.connectionsRequested).isZero();
  }

  @Test
  void aMappedWindowedAllIsAlsoJustADescriptionUntilSomethingSubscribes() {
    Flux<String> unsubscribedWindow =
        executor.all(
            FluentSelect.from(ACCOUNT),
            PageRequest.of(0, 20),
            OWNER_EMAIL);

    assertThat(unsubscribedWindow).isInstanceOf(Publisher.class);
    assertThat(connectionFactory.connectionsRequested).isZero();
  }

  @Test
  void aWindowedAllReportsABadSortPropertyThroughThePublisherRatherThanByThrowing() {
    Flux<Account> window =
        executor.all(
            FluentSelect.from(ACCOUNT),
            PageRequest.of(0, 10, Sort.by(NOT_A_PERSISTED_PROPERTY)));

    StepVerifier.create(window).expectError(IllegalArgumentException.class).verify();
    assertThat(connectionFactory.connectionsRequested).isZero();
  }

  @Test
  void aMappedWindowedAllReportsABadSortPropertyThroughThePublisherRatherThanByThrowing() {
    Flux<String> window =
        executor.all(
            FluentSelect.from(ACCOUNT),
            PageRequest.of(0, 10, Sort.by(NOT_A_PERSISTED_PROPERTY)),
            OWNER_EMAIL);

    StepVerifier.create(window).expectError(IllegalArgumentException.class).verify();
    assertThat(connectionFactory.connectionsRequested).isZero();
  }

  @Test
  void aWindowedAllRejectsAMissingDescriptionOrRequest() {
    assertThatNullPointerException()
        .isThrownBy(() -> executor.all(null, Pageable.unpaged()));

    // Cast because a bare null fits both the Pageable and the mapper overload of all(...).
    assertThatNullPointerException()
        .isThrownBy(() -> executor.all(FluentSelect.from(ACCOUNT), (Pageable) null));
  }

  @Test
  void aMappedWindowedAllRejectsAMissingDescriptionRequestOrMapper() {
    assertThatNullPointerException()
        .isThrownBy(() -> executor.all(null, Pageable.unpaged(), row -> row.read(ACCOUNT)));
    assertThatNullPointerException()
        .isThrownBy(() -> executor.all(FluentSelect.from(ACCOUNT), null, row -> row.read(ACCOUNT)));
    assertThatNullPointerException()
        .isThrownBy(() -> executor.all(FluentSelect.from(ACCOUNT), PageRequest.of(0, 10), null));
  }

  @Test
  void aMappedRowTerminalRejectsAMissingDescriptionOrMapper() {
    assertThatNullPointerException().isThrownBy(() -> executor.one(null, row -> row.read(ACCOUNT)));
    assertThatNullPointerException()
        .isThrownBy(() -> executor.one(FluentSelect.from(ACCOUNT), null));

    assertThatNullPointerException()
        .isThrownBy(() -> executor.first(null, row -> row.read(ACCOUNT)));
    assertThatNullPointerException()
        .isThrownBy(() -> executor.first(FluentSelect.from(ACCOUNT), null));

    assertThatNullPointerException()
        .isThrownBy(() -> executor.list(null, row -> row.read(ACCOUNT)));
    assertThatNullPointerException()
        .isThrownBy(() -> executor.list(FluentSelect.from(ACCOUNT), null));
  }

  @Test
  void aWindowCollisionReachesTheCallerAsASignalThroughEveryPagingTerminal() {
    // The translator throws, but it runs inside the terminal's defer, so the caller sees a signal.
    // Every terminal, because a new entry point that forgot rejectWindowCollision would
    // double-window silently.
    FluentSelect<Account> boundedAlready = FluentSelect.from(ACCOUNT).limit(500);

    StepVerifier.create(executor.page(boundedAlready, PageRequest.of(0, 10)))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(executor.slice(boundedAlready, PageRequest.of(0, 10)))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(executor.all(boundedAlready, PageRequest.of(0, 10)))
        .expectError(IllegalArgumentException.class)
        .verify();
    assertThat(connectionFactory.connectionsRequested).isZero();
  }

  @Test
  void aMissingCollaboratorOrDescriptionIsRejectedImmediately() {
    assertThatNullPointerException()
        .isThrownBy(() -> new MetamodelQueryExecutor(null, null, null));
    assertThatNullPointerException().isThrownBy(() -> executor.count(null));
    assertThatNullPointerException().isThrownBy(() -> executor.exists(null));
  }

  /**
   * A page's total must count the relation its content came from. A SpEL name is the only way to
   * observe that, being the one form re-read on every render.
   */
  @Test
  void aPageRendersItsTotalBeforeItReachesForAConnection() {
    TenantSchemaHolder tenant = new TenantSchemaHolder();
    RecordingConnectionFactory connections = new RecordingConnectionFactory(tenant);

    MetamodelQueryExecutor tenantedExecutor =
        executorFor(contextResolvingAgainst(tenant), connections);

    // Measured rather than assumed: the mapping context evaluates the expression more than once
    // per render, and that is its business, not this test's.
    StepVerifier.create(tenantedExecutor.count(FluentSelect.from(EntityRef.of(TenantAccount.class))))
        .expectError()
        .verify();

    int oneStatement = connections.schemaReadsWhenAskedForAConnection;

    // Without this the comparison below holds trivially when the expression is never evaluated.
    assertThat(oneStatement).isPositive();

    StepVerifier.create(
            tenantedExecutor.page(
                FluentSelect.from(EntityRef.of(TenantAccount.class)), PageRequest.of(0, 20)))
        .expectError()
        .verify();

    int beforeThePageReachedForAConnection =
        connections.schemaReadsWhenAskedForAConnection - oneStatement;

    assertThat(beforeThePageReachedForAConnection).isEqualTo(2 * oneStatement);
  }

  // Markers supplied rather than resolved: DatabaseClient.create() picks a factory from the
  // connection factory's driver name, and these factories name no driver.
  private static MetamodelQueryExecutor executorFor(
      R2dbcMappingContext mappingContext, ConnectionFactory connections) {

    return new MetamodelQueryExecutor(
        DatabaseClient.builder()
            .connectionFactory(connections)
            .bindMarkers(PostgresDialect.INSTANCE.getBindMarkersFactory())
            .build(),
        TestRenderers.postgres(mappingContext),
        new MappingR2dbcConverter(mappingContext));
  }

  private static R2dbcMappingContext contextResolvingAgainst(TenantSchemaHolder tenant) {
    GenericApplicationContext spring = new GenericApplicationContext();
    spring.getBeanFactory().registerSingleton("tenantSchemaHolder", tenant);
    spring.refresh();

    R2dbcMappingContext tenanted = new R2dbcMappingContext();
    tenanted.setApplicationContext(spring);
    tenanted.afterPropertiesSet();

    return tenanted;
  }

  @Table(value = "accounts", schema = "#{@tenantSchemaHolder.schema}")
  static class TenantAccount {

    @Id
    @Column("account_id")
    Long accountId;
  }

  public static final class TenantSchemaHolder {

    private int schemaReads;

    public String getSchema() {
      schemaReads++;

      return "tenant_a";
    }
  }

  @NullMarked
  private static final class RecordingConnectionFactory implements ConnectionFactory {

    private final TenantSchemaHolder tenant;

    private int schemaReadsWhenAskedForAConnection;

    private RecordingConnectionFactory(TenantSchemaHolder tenant) {
      this.tenant = tenant;
    }

    @Override
    public Publisher<? extends io.r2dbc.spi.Connection> create() {
      schemaReadsWhenAskedForAConnection = tenant.schemaReads;

      return Mono.error(new IllegalStateException("No connection in this suite"));
    }

    @Override
    public ConnectionFactoryMetadata getMetadata() {
      return () -> "recording";
    }
  }

  @NullMarked
  private static final class RefusingConnectionFactory implements ConnectionFactory {

    private int connectionsRequested;

    @Override
    public Publisher<? extends io.r2dbc.spi.Connection> create() {
      connectionsRequested++;

      throw new AssertionError("A connection was requested during assembly");
    }

    @Override
    public ConnectionFactoryMetadata getMetadata() {
      return () -> "refusing";
    }
  }
}
