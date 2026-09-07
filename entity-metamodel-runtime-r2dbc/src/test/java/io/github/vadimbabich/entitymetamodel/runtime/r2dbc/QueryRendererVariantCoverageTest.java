package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.ACCOUNT;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.MEMBERSHIP;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.accountId;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.membershipAccountId;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.ownerEmail;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.runtime.Comparison;
import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.ExpressionSort;
import io.github.vadimbabich.entitymetamodel.runtime.Inclusion;
import io.github.vadimbabich.entitymetamodel.runtime.Junction;
import io.github.vadimbabich.entitymetamodel.runtime.Negation;
import io.github.vadimbabich.entitymetamodel.runtime.NullCheck;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyEquality;
import io.github.vadimbabich.entitymetamodel.runtime.PropertySort;
import io.github.vadimbabich.entitymetamodel.runtime.SortOrder;
import io.github.vadimbabich.entitymetamodel.runtime.SqlExpr;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Sealing buys the renderer no compile-time exhaustiveness — {@code QueryRenderer.translate} ends
 * in a runtime refusal. A new permitted subtype fails here by name until it has both a fixture and
 * a rendering.
 */
class QueryRendererVariantCoverageTest {

  private static final Map<Class<? extends Condition>, FluentSelect<?>> CONDITION_FIXTURES =
      Map.of(
          Comparison.class, FluentSelect.from(ACCOUNT).where(ownerEmail().is("owner@example.com")),
          Inclusion.class, FluentSelect.from(ACCOUNT).where(accountId().in(List.of(1L, 2L))),
          Junction.class,
          FluentSelect.from(ACCOUNT)
              .where(ownerEmail().is("owner@example.com").and(accountId().gt(0L))),
          Negation.class,
          FluentSelect.from(ACCOUNT).where(ownerEmail().is("owner@example.com").not()),
          NullCheck.class, FluentSelect.from(ACCOUNT).where(ownerEmail().isNull()),
          PropertyEquality.class,
          FluentSelect.from(MEMBERSHIP)
              .join(ACCOUNT)
              .on(membershipAccountId().eq(accountId())),
          SqlExpr.class,
          FluentSelect.from(ACCOUNT).where(SqlExpr.raw("owner_email IS NOT NULL")));

  private static final Map<Class<? extends SortOrder>, FluentSelect<?>> SORT_FIXTURES =
      Map.of(
          PropertySort.class, FluentSelect.from(ACCOUNT).orderBy(ownerEmail().asc()),
          ExpressionSort.class,
          FluentSelect.from(ACCOUNT)
              .orderBy(ExpressionSort.asc(SqlExpr.raw("lower(owner_email)"))));

  private final QueryRenderer renderer = TestRenderers.postgres();

  @Test
  void everyPermittedConditionVariantHasAFixture() {
    assertThat(Condition.class.getPermittedSubclasses())
        .containsExactlyInAnyOrderElementsOf(CONDITION_FIXTURES.keySet());
  }

  @Test
  void everyPermittedSortVariantHasAFixture() {
    assertThat(SortOrder.class.getPermittedSubclasses())
        .containsExactlyInAnyOrderElementsOf(SORT_FIXTURES.keySet());
  }

  @Test
  void theRendererHandlesEveryConditionVariant() {
    for (Map.Entry<Class<? extends Condition>, FluentSelect<?>> fixture :
        CONDITION_FIXTURES.entrySet()) {
      RenderedStatement statement = renderer.render(fixture.getValue());

      assertThat(statement.sql()).as(fixture.getKey().getSimpleName()).isNotBlank();
    }
  }

  @Test
  void theRendererHandlesEverySortVariant() {
    for (Map.Entry<Class<? extends SortOrder>, FluentSelect<?>> fixture : SORT_FIXTURES.entrySet()) {
      RenderedStatement statement = renderer.render(fixture.getValue());

      assertThat(statement.sql()).as(fixture.getKey().getSimpleName()).isNotBlank();
    }
  }
}
