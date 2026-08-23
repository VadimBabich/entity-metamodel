package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.data.core.TypeInformation;
import org.springframework.data.r2dbc.convert.R2dbcConverter;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Runs the statements a {@link FluentSelect} describes, and the one collaborator a consumer wires
 * themselves: the library registers no component, reads no configuration and holds no static state.
 *
 * <p>It takes a {@link DatabaseClient} and a {@link QueryRenderer} rather than an
 * {@code R2dbcEntityTemplate}, which offers its dialect only as the narrower {@code Dialect} with
 * no bind-marker factory and its mapping context only behind a wildcard — deriving both would cost
 * two unchecked casts to save one line of wiring.
 *
 * <p>No terminal schedules work, sets a timeout, retries or subscribes; each returns a cold
 * publisher for the caller to decorate.
 */
public final class MetamodelQueryExecutor {

  private final DatabaseClient databaseClient;
  private final QueryRenderer renderer;
  private final R2dbcConverter converter;

  /**
   * The renderer and the converter must be built over the <em>same</em> mapping context: one names
   * a column when projecting it and the other when reading it back, so two contexts that disagree
   * produce rows whose labels nothing claims.
   */
  public MetamodelQueryExecutor(
      DatabaseClient databaseClient, QueryRenderer renderer, R2dbcConverter converter) {

    this.databaseClient = Objects.requireNonNull(databaseClient, "databaseClient");
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.converter = Objects.requireNonNull(converter, "converter");
  }

  /** Every matching row, streamed as the selected entity. */
  public <E> Flux<E> all(FluentSelect<E> select) {
    Objects.requireNonNull(select, "select");

    EntityRef<E> selected = select.entity();

    return all(select, row -> row.read(selected));
  }

  /**
   * Every matching row, mapped by the caller — how a row carrying more than one instance is read,
   * since each projected instance comes back separately instead of colliding on column names.
   */
  public <R> Flux<R> all(FluentSelect<?> select, Function<ProjectedRow, R> mapper) {
    Objects.requireNonNull(select, "select");
    Objects.requireNonNull(mapper, "mapper");

    RenderedStatement statement = renderer.render(select);

    return bind(statement)
        .fetch()
        .all()
        .map(projectedColumns -> mapper.apply(new ProjectedRow(projectedColumns, converter)));
  }

  /**
   * The single matching row: empty when nothing matches, an error when more than one does. Choosing
   * arbitrarily between candidates is what {@link #first(FluentSelect)} is for.
   */
  public <E> Mono<E> one(FluentSelect<E> select) {
    return all(select).singleOrEmpty();
  }

  /** The leading matching row. Which row leads is only defined if the description sorts. */
  public <E> Mono<E> first(FluentSelect<E> select) {
    Objects.requireNonNull(select, "select");

    return all(select.limit(1)).next();
  }

  /**
   * Every matching row, collected. Bound the description with a limit first: a collected terminal
   * over an unbounded result set holds the whole table in memory.
   */
  public <E> Mono<List<E>> list(FluentSelect<E> select) {
    return all(select).collectList();
  }

  /** How many rows the description matches, across the whole filtered set rather than one page. */
  public Mono<Long> count(FluentSelect<?> select) {
    Objects.requireNonNull(select, "select");

    RenderedStatement statement = renderer.renderCount(select);

    // Read by position: the count column's name is the dialect's business. A count is never SQL
    // NULL, so a null is a driver contract violation and belongs in the error signal, not a zero.
    return bind(statement)
        .map(row -> Objects.requireNonNull(row.get(0, Long.class), "count value"))
        .one();
  }

  /**
   * Whether anything matches, asked with a one-row probe rather than a full tally. An offset on the
   * description is honoured, so {@code exists(page.offset(end))} answers "is there another page".
   */
  public Mono<Boolean> exists(FluentSelect<?> select) {
    Objects.requireNonNull(select, "select");

    RenderedStatement statement = renderer.renderExistsProbe(select);

    return bind(statement).map(row -> Boolean.TRUE).first().defaultIfEmpty(Boolean.FALSE);
  }

  private DatabaseClient.GenericExecuteSpec bind(RenderedStatement statement) {
    return databaseClient.sql(statement.sql()).bindValues(convertedValues(statement));
  }

  /**
   * Values go through the converter rather than straight to the driver. An enum has no driver
   * encoding, and neither does any type that depends on a custom conversion, so filtering on one
   * fails at execution unless the converter that maps it on the way in is consulted on the way out.
   */
  private List<Object> convertedValues(RenderedStatement statement) {
    List<Object> converted = new ArrayList<>();

    for (Object value : statement.values()) {
      Object written = converter.writeValue(value, TypeInformation.of(value.getClass()));

      // No condition admits a null value, so only a converter can produce one here. Binding it
      // would fail in the driver with a message about the statement rather than the converter.
      if (written == null) {
        throw new IllegalStateException(
            "A converter mapped a " + value.getClass().getName()
                + " to null, which cannot be bound; a write converter must produce a value");
      }

      converted.add(written);
    }

    return converted;
  }
}
