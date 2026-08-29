package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.data.core.TypeInformation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.convert.R2dbcConverter;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Runs the statements a {@link FluentSelect} describes — the one collaborator a consumer wires
 * themselves, since the library registers no component and holds no static state.
 *
 * <p>Takes a {@link DatabaseClient} and {@link QueryRenderer} rather than an
 * {@code R2dbcEntityTemplate}, which exposes neither a bind-marker factory nor a typed mapping
 * context. Every terminal returns a cold publisher: no scheduling, timeout or retry.
 *
 * <p>Terminals live here rather than on the builder so that a description stays a pure value with
 * no executor in reach, which is how one description serves both a page and its count.
 */
public final class MetamodelQueryExecutor {

  private final DatabaseClient databaseClient;
  private final QueryRenderer renderer;
  private final R2dbcConverter converter;
  private final PageableTranslator pageableTranslator;

  /**
   * The renderer and converter must share one mapping context: one names a column when projecting
   * it and the other when reading it back, so two contexts produce rows nothing claims.
   */
  public MetamodelQueryExecutor(
      DatabaseClient databaseClient, QueryRenderer renderer, R2dbcConverter converter) {

    this.databaseClient = Objects.requireNonNull(databaseClient, "databaseClient");
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.converter = Objects.requireNonNull(converter, "converter");
    this.pageableTranslator = new PageableTranslator(renderer.mappingContext());
  }

  public <E> Flux<E> all(FluentSelect<E> select) {
    Objects.requireNonNull(select, "select");

    EntityRef<E> selected = select.entity();

    return all(select, row -> row.read(selected));
  }

  /**
   * The mapper form, for a row carrying more than one instance.
   */
  public <R> Flux<R> all(FluentSelect<?> select, Function<ProjectedRow, R> mapper) {
    Objects.requireNonNull(select, "select");
    Objects.requireNonNull(mapper, "mapper");

    RenderedStatement statement = renderer.render(select);

    return bind(statement)
        .fetch()
        .all()
        .map(
            projectedColumns ->
                mapper.apply(
                    new ProjectedRow(projectedColumns, converter, statement.aliases())));
  }

  /**
   * The single matching row: empty when nothing matches, an error when more than one does. Choosing
   * arbitrarily between candidates is what {@link #first(FluentSelect)} is for.
   */
  public <E> Mono<E> one(FluentSelect<E> select) {
    Objects.requireNonNull(select, "select");

    // Counted rather than inferred from Reactor's index fault, which would also catch one thrown
    // while reading a row.
    return all(select).take(2).collectList().flatMap(MetamodelQueryExecutor::exactlyOne);
  }

  private static <E> Mono<E> exactlyOne(List<E> rows) {
    if (rows.isEmpty()) {
      return Mono.empty();
    }
    if (rows.size() > 1) {
      return Mono.error(
          new IllegalStateException(
              "This description matched more than one row; narrow the filter, or use first(...) to"
                  + " choose between candidates. A join to a to-many side matches once per"
                  + " counterpart"));
    }

    return Mono.just(rows.get(0));
  }

  /**
   * The leading matching row — only defined if the description sorts.
   */
  public <E> Mono<E> first(FluentSelect<E> select) {
    Objects.requireNonNull(select, "select");

    return all(select.withAtMostOneRow()).next();
  }

  /**
   * Bound the description first: this holds the whole result in memory.
   */
  public <E> Mono<List<E>> list(FluentSelect<E> select) {
    Objects.requireNonNull(select, "select");

    return all(select).collectList();
  }

  /**
   * How many rows the description matches, across the whole filtered set rather than one page.
   * Rows, not entities: a join to a to-many side matches once per counterpart — unless the
   * description is distinct, when the total is its distinct rows.
   */
  public Mono<Long> count(FluentSelect<?> select) {
    Objects.requireNonNull(select, "select");

    RenderedStatement statement = renderer.renderCount(select);

    // By position, because the count column's name is the dialect's business; a null is a driver
    // fault rather than a zero, since a count is never SQL NULL. first() rather than one(): a COUNT
    // without GROUP BY returns one row by construction, while one() would reshape a binding fault
    // into "returned non unique result" and hide the driver's real error.
    return bind(statement)
        .map(row -> Objects.requireNonNull(row.get(0, Long.class), "count value"))
        .first();
  }

  /**
   * Whether anything matches, as a one-row probe. The description's offset is honoured, so
   * {@code exists(page.offset(end))} answers "is there another page".
   */
  public Mono<Boolean> exists(FluentSelect<?> select) {
    Objects.requireNonNull(select, "select");

    RenderedStatement statement = renderer.renderExistsProbe(select);

    return bind(statement).map(row -> Boolean.TRUE).first().defaultIfEmpty(Boolean.FALSE);
  }

  /**
   * One page of matching rows, with the total behind it at the cost of a second statement. The
   * request's sort leads any the description carries, and a to-many join makes both the content and
   * the total count rows rather than entities — unless the description is distinct, when both are
   * its distinct rows ({@link FluentSelect#distinct()}).
   *
   * <p>Only a missing argument throws; everything else — including a sort property the entity does
   * not persist — arrives as an error signal.
   */
  public <E> Mono<Page<E>> page(FluentSelect<E> select, Pageable pageable) {
    Objects.requireNonNull(select, "select");
    Objects.requireNonNull(pageable, "pageable");

    return Mono.defer(
        () -> {
          FluentSelect<E> onePage = pageableTranslator.applyTo(select, pageable);

          // Sequential, not zipped: inside a transaction both statements share one connection, and
          // two at once on one connection is a protocol error.
          return list(onePage).flatMap(rows -> pageOf(rows, select, pageable));
        });
  }

  private <E> Mono<Page<E>> pageOf(
      List<E> rows, FluentSelect<E> select, Pageable pageable) {

    if (pageable.isUnpaged() && select.limit().isEmpty() && select.offset().isEmpty()) {
      // Nothing was left behind, so the rows in hand are the total. A description with a window of
      // its own does leave rows behind — an offset as much as a limit — so it takes the count.
      return Mono.just(new PageImpl<>(rows, pageable, rows.size()));
    }

    return count(select).map(matchedRows -> new PageImpl<>(rows, pageable, matchedRows));
  }

  private DatabaseClient.GenericExecuteSpec bind(RenderedStatement statement) {
    return databaseClient.sql(statement.sql()).bindValues(convertedValues(statement));
  }

  // Through the converter, not straight to the driver: an enum has no driver encoding, and neither
  // has any type that depends on a custom conversion.
  private List<Object> convertedValues(RenderedStatement statement) {
    List<Object> converted = new ArrayList<>();

    for (Object value : statement.values()) {
      Object written = converter.writeValue(value, TypeInformation.of(value.getClass()));

      // No condition admits null, so only a converter can produce one — and the driver would blame
      // the statement.
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
