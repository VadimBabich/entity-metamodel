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
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;
import org.springframework.r2dbc.core.DatabaseClient;
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

  private final RefusingConnectionFactory connectionFactory = new RefusingConnectionFactory();

  // Markers supplied rather than resolved: DatabaseClient.create() picks a factory from the
  // connection factory's driver name, and this one names no driver.
  private final MetamodelQueryExecutor executor =
      new MetamodelQueryExecutor(
          DatabaseClient.builder()
              .connectionFactory(connectionFactory)
              .bindMarkers(PostgresDialect.INSTANCE.getBindMarkersFactory())
              .build(),
          new QueryRenderer(MAPPING_CONTEXT, PostgresDialect.INSTANCE),
          new MappingR2dbcConverter(MAPPING_CONTEXT));

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

    // page and slice hydrate as well, so they refuse too — but they take request data, which puts
    // the refusal in the publisher rather than in a throw.
    assertThatNoException().isThrownBy(() -> executor.page(people, PageRequest.of(0, 10)));
    assertThatNoException().isThrownBy(() -> executor.slice(people, PageRequest.of(0, 10)));
  }

  @Test
  void pageReportsTheSameRefusalThroughThePublisherRatherThanByThrowing() {
    // The same description throws from all() and signals from page().
    FluentSelect<Person> people = FluentSelect.from(PERSON);

    StepVerifier.create(executor.page(people, PageRequest.of(0, 10)))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(executor.page(people, PageRequest.of(0, 10), row -> row.read(PERSON)))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void sliceReportsTheSameRefusalThroughThePublisherRatherThanByThrowing() {
    FluentSelect<Person> people = FluentSelect.from(PERSON);

    StepVerifier.create(executor.slice(people, PageRequest.of(0, 10)))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(
            executor.slice(people, PageRequest.of(0, 10), row -> row.read(PERSON)))
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
        executor.page(FluentSelect.from(ACCOUNT), PageRequest.of(0, 10, Sort.by("nickname")));

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
            row -> row.read(ACCOUNT).ownerEmail);

    assertThat(unsubscribedPage).isInstanceOf(Publisher.class);
    assertThat(connectionFactory.connectionsRequested).isZero();
  }

  @Test
  void aMappedPageReportsABadSortPropertyThroughThePublisherRatherThanByThrowing() {
    Mono<Page<String>> page =
        executor.page(
            FluentSelect.from(ACCOUNT),
            PageRequest.of(0, 10, Sort.by("nickname")),
            row -> row.read(ACCOUNT).ownerEmail);

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
            row -> row.read(ACCOUNT).ownerEmail);

    assertThat(unsubscribedSlice).isInstanceOf(Publisher.class);
    assertThat(connectionFactory.connectionsRequested).isZero();
  }

  @Test
  void aSliceReportsABadSortPropertyThroughThePublisherRatherThanByThrowing() {
    Mono<Slice<Account>> slice =
        executor.slice(FluentSelect.from(ACCOUNT), PageRequest.of(0, 10, Sort.by("nickname")));

    StepVerifier.create(slice).expectError(IllegalArgumentException.class).verify();
    assertThat(connectionFactory.connectionsRequested).isZero();
  }

  @Test
  void aMappedSliceReportsABadSortPropertyThroughThePublisherRatherThanByThrowing() {
    Mono<Slice<String>> slice =
        executor.slice(
            FluentSelect.from(ACCOUNT),
            PageRequest.of(0, 10, Sort.by("nickname")),
            row -> row.read(ACCOUNT).ownerEmail);

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
  void aWindowCollisionReachesTheCallerAsASignalThroughEitherPagingTerminal() {
    // The translator throws, but it runs inside the terminal's defer, so the caller sees a signal.
    // Both terminals, because a new entry point that forgot rejectWindowCollision would
    // double-window silently.
    FluentSelect<Account> boundedAlready = FluentSelect.from(ACCOUNT).limit(500);

    StepVerifier.create(executor.page(boundedAlready, PageRequest.of(0, 10)))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(executor.slice(boundedAlready, PageRequest.of(0, 10)))
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
