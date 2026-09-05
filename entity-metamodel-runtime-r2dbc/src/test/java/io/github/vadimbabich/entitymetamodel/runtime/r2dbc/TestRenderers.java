package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;

/**
 * The renderer wiring the rendering suites share. A fresh instance per call, never a cached one:
 * that renders do not affect each other is what {@link StatementInvariantsTest} proves, and a
 * shared renderer would assume it instead.
 */
final class TestRenderers {

  private TestRenderers() {
  }

  static QueryRenderer postgres() {
    return postgres(new R2dbcMappingContext());
  }

  static QueryRenderer postgres(R2dbcMappingContext mappingContext) {
    return new QueryRenderer(mappingContext, PostgresDialect.INSTANCE);
  }
}
