package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.springframework.data.core.TypeInformation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.r2dbc.convert.R2dbcConverter;
import org.springframework.data.support.ReactivePageableExecutionUtils;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Runs the statements a {@link FluentSelect} describes. The library registers no component and
 * holds no static state, so this is the one collaborator a consumer wires themselves.
 *
 * <p>Takes a {@link DatabaseClient} and {@link QueryRenderer} rather than an
 * {@code R2dbcEntityTemplate}, which exposes neither a bind-marker factory nor a typed mapping
 * context. Every terminal returns a cold publisher: no scheduling, timeout or retry.
 */
public final class MetamodelQueryExecutor {

  private final DatabaseClient databaseClient;
  private final QueryRenderer renderer;
  private final R2dbcConverter converter;
  private final PageableTranslator pageableTranslator;

  // A projection's shape depends only on the result type and the entity, so it is resolved once
  // rather than rebuilt on every row.
  private final ProjectionResolver projections;

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
    this.projections = new ProjectionResolver(converter);
  }

  public <E> Flux<E> all(FluentSelect<E> select) {
    Objects.requireNonNull(select, "select");

    EntityRef<E> selected = select.entity();

    return all(select, row -> row.read(selected));
  }

  /** The mapper form, for a row carrying more than one instance. */
  public <R> Flux<R> all(FluentSelect<?> select, Function<ProjectedRow, R> mapper) {
    Objects.requireNonNull(select, "select");
    Objects.requireNonNull(mapper, "mapper");

    return projectedRows(select).map(mapper);
  }

  private Flux<ProjectedRow> projectedRows(FluentSelect<?> select) {
    RenderedStatement statement = renderer.render(select);

    return bind(statement)
        .fetch()
        .all()
        .map(
            projectedColumns ->
                new ProjectedRow(projectedColumns, converter, statement.aliases(), projections));
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

  /** The leading matching row — only defined if the description sorts. */
  public <E> Mono<E> first(FluentSelect<E> select) {
    Objects.requireNonNull(select, "select");

    return all(select.withAtMostOneRow()).next();
  }

  /** Bound the description first: this holds the whole result in memory. */
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

    return totalFrom(renderer.renderCount(select));
  }

  // By position, because the count column's name is the dialect's business; a null is a driver
  // fault rather than a zero. first() rather than one(): a COUNT without GROUP BY returns one row
  // by construction, and one() would reshape a binding fault into "non unique result".
  private Mono<Long> totalFrom(RenderedStatement statement) {
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
   * One page of matching rows with the total behind it. The count costs a second statement only
   * where the rows in hand cannot imply the total, which includes an empty page past the beginning:
   * it says the offset overshot, not by how much. Where the total is never displayed, prefer
   * {@link #slice(FluentSelect, Pageable)}, which never counts at all.
   *
   * <p>The request's sort leads any the description carries, and a to-many join counts rows rather
   * than entities unless the description is {@link FluentSelect#distinct()}.
   *
   * <p>Only a missing argument throws; a sort property the entity does not persist arrives as an
   * error signal.
   */
  public <E> Mono<Page<E>> page(FluentSelect<E> select, Pageable pageable) {
    Objects.requireNonNull(select, "select");

    EntityRef<E> selected = select.entity();

    return page(select, pageable, row -> row.read(selected));
  }

  /**
   * The mapper form, for a page whose rows carry more than one instance or are read back as
   * projections. Sort, total and count elision behave as they do for entities.
   *
   * <p>Sort terms resolve against the description's root instance, not against what the mapper
   * reads: a name both tables persist orders by the root with nothing to notice. An
   * {@code orderBy} cannot correct that, since the request's terms lead — root the description at
   * the instance being listed instead.
   */
  public <R> Mono<Page<R>> page(
      FluentSelect<?> select, Pageable pageable, Function<ProjectedRow, R> mapper) {

    Objects.requireNonNull(select, "select");
    Objects.requireNonNull(pageable, "pageable");
    Objects.requireNonNull(mapper, "mapper");

    return Mono.defer(
        () -> {
          FluentSelect<?> onePage = pageableTranslator.applyTo(select, pageable);

          // Rendered here rather than in the branch that needs it: a dynamically resolved name
          // would otherwise be read again on the thread that delivered the last row, and the total
          // would count a relation the content never came from. Rendering performs no I/O, so a
          // page that elides the count discards a statement rather than paying for one.
          RenderedStatement total = renderer.renderCount(select);

          // Sequential, not zipped: inside a transaction both statements share one connection, and
          // two at once on one connection is a protocol error.
          return all(onePage, mapper)
              .collectList()
              .flatMap(rows -> pageOf(rows, total, select, pageable));
        });
  }

  /**
   * One page of matching rows and whether another follows, without the count a page pays for: one
   * row beyond the page is fetched, and its presence is the answer. {@code Page} already answers
   * {@code hasNext()}, so what this buys is the avoided <em>statement</em>, not a new shape.
   *
   * <p>A to-many join matches once per counterpart, so a slice of a multiplied description holds
   * duplicates unless it is {@link FluentSelect#distinct()}. An unpaged request is one slice
   * reporting no successor, even where the description's own window left rows behind.
   *
   * <p>Only a missing argument throws; a sort property the entity does not persist arrives as an
   * error signal.
   */
  public <E> Mono<Slice<E>> slice(FluentSelect<E> select, Pageable pageable) {
    Objects.requireNonNull(select, "select");

    EntityRef<E> selected = select.entity();

    return slice(select, pageable, row -> row.read(selected));
  }

  /**
   * The mapper form, for a slice whose rows carry more than one instance or are read back as
   * projections. The row fetched to prove a successor is discarded before it reaches the mapper.
   */
  public <R> Mono<Slice<R>> slice(
      FluentSelect<?> select, Pageable pageable, Function<ProjectedRow, R> mapper) {

    Objects.requireNonNull(select, "select");
    Objects.requireNonNull(pageable, "pageable");
    Objects.requireNonNull(mapper, "mapper");

    return Mono.defer(
        () -> {
          PageableTranslator.ProbedWindow<?> probedWindow =
              pageableTranslator.applyToWithProbeRow(select, pageable);

          if (probedWindow.rowsPerPage().isEmpty()) {
            return all(probedWindow.select(), mapper)
                .collectList()
                .map(everyRow -> new SliceImpl<>(everyRow, pageable, false));
          }

          // From the window that was asked for, so the cut-off cannot drift from the probe row.
          long rowsPerPage = probedWindow.rowsPerPage().getAsLong();

          // Counted rather than collected: trimming a materialised list would hold the raw result
          // set beside the mapped one, so the probe row is dropped as it streams. The counter sits
          // inside the defer, so each subscription counts its own rows.
          AtomicLong fetchedRows = new AtomicLong();

          return projectedRows(probedWindow.select())
              .filter(row -> fetchedRows.incrementAndGet() <= rowsPerPage)
              .map(mapper)
              .collectList()
              .map(
                  pageRows ->
                      new SliceImpl<>(pageRows, pageable, fetchedRows.get() > rowsPerPage));
        });
  }

  private <R> Mono<Page<R>> pageOf(
      List<R> rows, RenderedStatement total, FluentSelect<?> select, Pageable pageable) {

    if (pageable.isUnpaged()) {
      return unpagedPageOf(rows, total, select, pageable);
    }

    // The substrate skips the count wherever the rows in hand already determine the total. Sound
    // only because the request brought the window, which means rejectWindowCollision has already
    // proved the description carries none of its own. Deferred because getPage does not subscribe
    // the count on every branch; the statement it would run is already rendered.
    return ReactivePageableExecutionUtils.getPage(
        rows, pageable, Mono.defer(() -> totalFrom(total)));
  }

  // Not delegated: the substrate's unpaged branch reports the rows in hand as the total, which
  // fabricates one whenever the description carries a window of its own — an offset as much as a
  // limit.
  private <R> Mono<Page<R>> unpagedPageOf(
      List<R> rows, RenderedStatement total, FluentSelect<?> select, Pageable pageable) {

    if (select.limit().isEmpty() && select.offset().isEmpty()) {
      return Mono.just(new PageImpl<>(rows, pageable, rows.size()));
    }

    return totalFrom(total).map(matchedRows -> new PageImpl<>(rows, pageable, matchedRows));
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
