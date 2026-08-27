package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.Comparison;
import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.Inclusion;
import io.github.vadimbabich.entitymetamodel.runtime.NullCheck;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import io.github.vadimbabich.entitymetamodel.runtime.SqlExpr;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.query.CriteriaDefinition;
import org.springframework.data.relational.core.query.ValueFunction;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.data.util.Pair;

/**
 * Translates a substrate {@code Criteria} into the owned condition vocabulary, so a filter an
 * application already builds can narrow a {@link FluentSelect} unchanged. {@code
 * CriteriaDefinition} is admitted deliberately: it is the substrate's user-facing vocabulary, not
 * its internals.
 *
 * <p>The same criteria selects the same rows as through an {@code R2dbcEntityTemplate}, held by an
 * integration test comparing row sets. What it cannot express faithfully it refuses.
 */
public final class CriteriaAdapter {

  // Held as a name because naming the type would raise a deprecation warning, and this module
  // compiles warnings as errors.
  private static final String WRAPPED_VALUE_TYPE = "org.springframework.r2dbc.core.Parameter";

  private final PropertyNameResolver propertyNames;

  /**
   * Pass the same context the {@link QueryRenderer} resolves against: a name this accepts must be a
   * name that renders.
   */
  public CriteriaAdapter(RelationalMappingContext mappingContext) {
    Objects.requireNonNull(mappingContext, "mappingContext");

    this.propertyNames = new PropertyNameResolver(mappingContext);
  }

  /**
   * The criteria as a condition over the instance's properties, or empty when it restricts nothing
   * — a filter layer with nothing to filter is ordinary, and {@code WHERE 1 = 1} serves no one.
   */
  public <E> Optional<Condition> toCondition(CriteriaDefinition criteria, EntityRef<E> instance) {
    Objects.requireNonNull(criteria, "criteria");
    Objects.requireNonNull(instance, "instance");

    return conditionOf(criteria, instance);
  }

  // A chain is a flat sequence under SQL precedence, not a left-associated tree: the substrate
  // emits it unbracketed, so `a OR b AND c` executes as `a OR (b AND c)`. An OR therefore opens a
  // new run, anything else extends the current one, and the runs are OR-ed at the end.
  private <E> Optional<Condition> conditionOf(
      CriteriaDefinition criteria, EntityRef<E> instance) {

    List<Condition> runs = new ArrayList<>();

    for (CriteriaDefinition link : forwardChain(criteria)) {
      Optional<Condition> term = ownConditionOf(link, instance);

      if (term.isEmpty()) {
        continue;
      }
      if (runs.isEmpty() || link.getCombinator() != CriteriaDefinition.Combinator.OR) {
        extendLastRun(runs, term.get());
      } else {
        runs.add(term.get());
      }
    }

    return foldWithOr(runs);
  }

  private static List<CriteriaDefinition> forwardChain(CriteriaDefinition criteria) {
    List<CriteriaDefinition> chain = new ArrayList<>();

    CriteriaDefinition link = criteria;
    while (link != null) {
      chain.add(link);
      link = link.hasPrevious() ? link.getRequiredPrevious() : null;
    }

    Collections.reverse(chain);

    return chain;
  }

  private static void extendLastRun(List<Condition> runs, Condition term) {
    if (runs.isEmpty()) {
      runs.add(term);
      return;
    }

    int last = runs.size() - 1;
    runs.set(last, runs.get(last).and(term));
  }

  private static Optional<Condition> foldWithOr(List<Condition> runs) {
    if (runs.isEmpty()) {
      return Optional.empty();
    }

    Condition folded = runs.get(0);
    for (Condition further : runs.subList(1, runs.size())) {
      folded = folded.or(further);
    }

    return Optional.of(folded);
  }

  private static Condition join(
      Condition preceding, Condition own, CriteriaDefinition.Combinator combinator) {

    if (combinator == CriteriaDefinition.Combinator.OR) {
      return preceding.or(own);
    }

    return preceding.and(own);
  }

  private <E> Optional<Condition> ownConditionOf(
      CriteriaDefinition criteria, EntityRef<E> instance) {

    if (criteria.isGroup()) {
      return groupConditionOf(criteria.getGroup(), criteria.getCombinator(), instance);
    }
    if (criteria.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(comparisonOf(criteria, instance));
  }

  // One combinator joins every member, as the substrate's mapper does. Reading it off each member
  // instead turns `from(scope, search)` into `scope OR search`, and the scope stops constraining.
  private <E> Optional<Condition> groupConditionOf(
      List<CriteriaDefinition> group, CriteriaDefinition.Combinator combinator,
      EntityRef<E> instance) {

    Optional<Condition> combined = Optional.empty();

    for (CriteriaDefinition member : group) {
      Optional<Condition> memberCondition = conditionOf(member, instance);

      if (memberCondition.isEmpty()) {
        continue;
      }
      if (combined.isEmpty()) {
        combined = memberCondition;
        continue;
      }

      combined = Optional.of(join(combined.get(), memberCondition.get(), combinator));
    }

    return combined;
  }

  private <E> Condition comparisonOf(CriteriaDefinition criteria, EntityRef<E> instance) {
    SqlIdentifier column = criteria.getColumn();
    CriteriaDefinition.Comparator comparator = criteria.getComparator();

    // Both are nullable on the interface. Spring's builder always sets them; a converter
    // implementing the interface itself might not.
    if (column == null || comparator == null) {
      throw new IllegalArgumentException(
          "A criteria that is neither a group nor empty must name a column and a comparator, but"
              + " this one names column " + column + " and comparator " + comparator);
    }

    String propertyName = column.getReference();
    rejectIgnoredCase(criteria, propertyName);

    PropertyRef<E, ?> property = propertyNames.resolve(instance, propertyName);
    Object value = criteria.getValue();
    rejectDeferredValue(value, propertyName);

    return switch (comparator) {
      // Equality against nothing asks about absence, as the substrate's mapper reads it; '= NULL'
      // would match no row and report nothing.
      case EQ -> nullSafeEquality(property, value);
      case NEQ -> nullSafeEquality(property, value).not();
      case LT -> comparison(property, Comparison.Operator.LESS_THAN, value);
      case LTE -> comparison(property, Comparison.Operator.LESS_THAN_OR_EQUAL, value);
      case GT -> comparison(property, Comparison.Operator.GREATER_THAN, value);
      case GTE -> comparison(property, Comparison.Operator.GREATER_THAN_OR_EQUAL, value);
      case LIKE -> comparison(property, Comparison.Operator.LIKE, value);
      case NOT_LIKE -> comparison(property, Comparison.Operator.LIKE, value).not();
      case IN -> inclusion(property, value);
      case NOT_IN -> inclusion(property, value).not();
      case IS_NULL -> new NullCheck(property);
      case IS_NOT_NULL -> new NullCheck(property).not();
      case IS_TRUE -> comparison(property, Comparison.Operator.EQUAL, Boolean.TRUE);
      case IS_FALSE -> comparison(property, Comparison.Operator.EQUAL, Boolean.FALSE);
      case BETWEEN -> range(property, value);
      case NOT_BETWEEN -> range(property, value).not();
      case INITIAL -> throw new IllegalArgumentException(
          "A criteria with no comparator restricts nothing and should not have reached here");
    };
  }

  private static Condition nullSafeEquality(PropertyRef<?, ?> property, Object value) {
    if (value == null) {
      return new NullCheck(property);
    }

    return comparison(property, Comparison.Operator.EQUAL, value);
  }

  private static Condition comparison(
      PropertyRef<?, ?> property, Comparison.Operator operator, Object value) {

    return new Comparison(property, operator, value);
  }

  private static Condition inclusion(PropertyRef<?, ?> property, Object value) {
    if (value instanceof Collection<?> values) {
      // The substrate renders a cleared filter as a false condition. Inclusion refuses an empty
      // list on purpose, and this is the one caller that must not inherit the refusal; NOT IN
      // negates it to match everything, as the substrate does.
      if (values.isEmpty()) {
        return SqlExpr.raw("1 = 0");
      }

      return new Inclusion(property, List.copyOf(values));
    }

    throw new IllegalArgumentException(
        "An IN criteria on '" + property.name() + "' carries "
            + describe(value) + " rather than a collection of values");
  }

  // Its two bounds: the rendered form differs from the substrate's, the rows do not.
  private static Condition range(PropertyRef<?, ?> property, Object value) {
    if (value instanceof Pair<?, ?> bounds) {
      Condition atLeastLower =
          comparison(property, Comparison.Operator.GREATER_THAN_OR_EQUAL, bounds.getFirst());
      Condition atMostUpper =
          comparison(property, Comparison.Operator.LESS_THAN_OR_EQUAL, bounds.getSecond());

      return atLeastLower.and(atMostUpper);
    }

    throw new IllegalArgumentException(
        "A range criteria on '" + property.name() + "' carries "
            + describe(value) + " rather than a pair of bounds");
  }

  // The substrate resolves a ValueFunction against an escaper picked from the comparator and the
  // dialect; this door holds neither, and passing the lambda down the bind path fails in the driver
  // with a message about the statement rather than the value.
  private static void rejectDeferredValue(Object value, String propertyName) {
    if (value instanceof ValueFunction<?>) {
      throw new IllegalArgumentException(
          "The criteria on '" + propertyName + "' carries a ValueFunction, which resolves against"
              + " a dialect escaper this door does not have; apply it yourself and pass the"
              + " resulting value, or state the pattern through SqlExpr");
    }

    // Refused rather than compared to the wrapper, which would render an equality against an object
    // where the substrate reads an empty one as IS NULL.
    if (value != null && WRAPPED_VALUE_TYPE.equals(value.getClass().getName())) {
      throw new IllegalArgumentException(
          "The criteria on '" + propertyName + "' wraps its value in a deprecated Parameter, which"
              + " this door does not unwrap; pass the value itself, or null for absence");
    }
  }

  private static String describe(Object value) {
    if (value == null) {
      return "nothing";
    }

    return value.getClass().getName();
  }

  // Refused rather than dropped: made case-sensitive silently, it returns fewer rows than asked
  // for, with nothing to notice.
  private static void rejectIgnoredCase(CriteriaDefinition criteria, String propertyName) {
    if (criteria.isIgnoreCase()) {
      throw new IllegalArgumentException(
          "The criteria on '" + propertyName + "' ignores case, which this vocabulary cannot"
              + " express; state it as an SqlExpr fragment over the lower-cased column");
    }
  }
}
