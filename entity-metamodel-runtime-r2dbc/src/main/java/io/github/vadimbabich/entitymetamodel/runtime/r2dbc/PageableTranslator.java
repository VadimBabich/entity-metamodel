package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import io.github.vadimbabich.entitymetamodel.runtime.SortOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;

/**
 * Turns a {@code Pageable} into the owned description, resolving sort terms against the selected
 * instance — a name arriving as text cannot say which side of a join it means.
 */
final class PageableTranslator {

  private static final long WITHOUT_A_PROBE_ROW = 0L;
  private static final long WITH_A_PROBE_ROW = 1L;

  private final PropertyNameResolver propertyNames;

  PageableTranslator(RelationalMappingContext mappingContext) {
    this.propertyNames = new PropertyNameResolver(mappingContext);
  }

  <E> FluentSelect<E> applyTo(FluentSelect<E> select, Pageable pageable) {
    return applySortAndWindow(select, pageable, WITHOUT_A_PROBE_ROW).select();
  }

  /**
   * The same window with one row beyond it: whether that row comes back is the whole answer to
   * "is there another page", which is what lets a caller report one without counting.
   */
  <E> ProbedWindow<E> applyToWithProbeRow(FluentSelect<E> select, Pageable pageable) {
    return applySortAndWindow(select, pageable, WITH_A_PROBE_ROW);
  }

  private <E> ProbedWindow<E> applySortAndWindow(
      FluentSelect<E> select, Pageable pageable, long probeRows) {

    Objects.requireNonNull(select, "select");
    Objects.requireNonNull(pageable, "pageable");

    FluentSelect<E> sorted = applySort(select, pageable.getSort());

    if (pageable.isUnpaged()) {
      // Nothing below may be hoisted above this branch: an unpaged request has no page size, and
      // asking for one throws.
      return new ProbedWindow<>(sorted, OptionalLong.empty());
    }

    rejectWindowCollision(select);

    // In long arithmetic because Integer.MAX_VALUE is a legal page size: in int arithmetic the
    // probe row wraps it to a negative limit, which the description then refuses outright.
    long rowsPerPage = pageable.getPageSize();
    long windowedRows = rowsPerPage + probeRows;

    FluentSelect<E> windowed = sorted.limit(windowedRows).offset(pageable.getOffset());

    return new ProbedWindow<>(windowed, OptionalLong.of(rowsPerPage));
  }

  // Two windows over one statement. Overwriting loses a deliberate cap silently, and in the offset
  // direction returns exactly the rows the description said to skip; composing them is a decision
  // rather than a fix.
  private void rejectWindowCollision(FluentSelect<?> select) {
    if (select.limit().isPresent()) {
      throw twoWindows("limit", select.limit().getAsLong());
    }
    if (select.offset().isPresent()) {
      throw twoWindows("offset", select.offset().getAsLong());
    }
  }

  private static IllegalArgumentException twoWindows(String what, long bound) {
    return new IllegalArgumentException(
        "This description already carries a " + what + " of " + bound
            + ", and a page request brings its own; page a description that does not bound"
            + " itself, or apply the page window yourself and ask for list(...)");
  }

  // The request's sort leads, the description's follows as the tiebreaker. Appending would bury the
  // request behind a leading key that is usually unique, where it can change nothing.
  private <E> FluentSelect<E> applySort(FluentSelect<E> select, Sort sort) {
    if (sort.isUnsorted()) {
      return select;
    }

    List<SortOrder> sortOrders = new ArrayList<>();
    for (Sort.Order order : sort) {
      sortOrders.add(sortOrderOf(select.entity(), order));
    }
    sortOrders.addAll(select.sortOrders());

    return select.withoutSort().orderBy(sortOrders.toArray(SortOrder[]::new));
  }

  private <E> SortOrder sortOrderOf(EntityRef<E> instance, Sort.Order order) {
    rejectUnsupportedOptions(order);

    PropertyRef<E, ?> property = propertyNames.resolve(instance, order.getProperty());

    if (order.isAscending()) {
      return property.asc();
    }

    return property.desc();
  }

  // Both are real SQL and neither is in this vocabulary. Refused rather than ignored: a dropped
  // sort option returns rows in an order the caller did not ask for, with nothing to notice.
  private void rejectUnsupportedOptions(Sort.Order order) {
    if (order.isIgnoreCase()) {
      throw new IllegalArgumentException(
          "Sorting on '" + order.getProperty() + "' ignoring case is not expressible as a property"
              + " sort; state it as an ExpressionSort over the lower-cased column");
    }

    if (order.getNullHandling() != Sort.NullHandling.NATIVE) {
      throw new IllegalArgumentException(
          "Sorting on '" + order.getProperty() + "' with " + order.getNullHandling()
              + " null handling is not expressible as a property sort; state it as an"
              + " ExpressionSort, or leave the dialect's own null ordering in place");
    }
  }

  /** A windowed description and the rows one page of it holds; empty when unpaged. */
  record ProbedWindow<E>(FluentSelect<E> select, OptionalLong rowsPerPage) {}
}
