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
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

/**
 * Describing a query must cost nothing until something subscribes. This runs without Docker on
 * purpose: the guarantee is about assembly, and a connection factory that refuses to hand out
 * connections proves it more sharply than a working database ever could.
 */
class ExecutorAssemblyTest {

  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);
  private static final R2dbcMappingContext MAPPING_CONTEXT = new R2dbcMappingContext();

  private final RefusingConnectionFactory connectionFactory = new RefusingConnectionFactory();

  // The markers are supplied rather than resolved: DatabaseClient.create() picks a
  // BindMarkersFactory from the connection factory's driver name, and this one names no driver.
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
    FluentSelect<Person> people = FluentSelect.from(EntityRef.of(Person.class));

    // The refusal exists because hydration would drop the embedded value silently. Counting and
    // probing hydrate nothing, so refusing them would cost a capability and buy no safety.
    assertThatNoException().isThrownBy(() -> executor.count(people));
    assertThatNoException().isThrownBy(() -> executor.exists(people));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> executor.all(people))
        .withMessageContaining("embedded");
  }

  @Test
  void aMissingCollaboratorOrDescriptionIsRejectedImmediately() {
    assertThatNullPointerException()
        .isThrownBy(() -> new MetamodelQueryExecutor(null, null, null));
    assertThatNullPointerException().isThrownBy(() -> executor.count(null));
    assertThatNullPointerException().isThrownBy(() -> executor.exists(null));
  }

  /** Fails the moment anything asks for a connection, and records that it was asked. */
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
