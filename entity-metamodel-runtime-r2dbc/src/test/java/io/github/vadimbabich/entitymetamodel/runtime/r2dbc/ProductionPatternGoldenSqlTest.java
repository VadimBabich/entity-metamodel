package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;

/**
 * Whole statements for the shapes in {@link ProductionPatterns}, compared against committed files.
 * The other rendering suites assert the fragment each is about; this is the only place a change to
 * any part of a realistic statement has to be looked at and accepted.
 *
 * <p>A golden holds the SQL on its first line, then one {@code marker = value} line per binding in
 * allocation order, so a statement and the values it binds cannot drift apart unnoticed. To update
 * one, read the diff this test prints, satisfy yourself the new statement is the intended one, and
 * write it to the named file — never regenerate in bulk.
 */
class ProductionPatternGoldenSqlTest {

  private static final String GOLDEN_DIRECTORY = "golden-sql";

  private final R2dbcMappingContext mappingContext = new R2dbcMappingContext();
  private final QueryRenderer renderer =
      new QueryRenderer(mappingContext, PostgresDialect.INSTANCE);

  @Test
  void aListingScopedToOnePrincipalRendersAsRecorded() {
    assertMatchesGolden("scoped-listing", renderer.render(ProductionPatterns.scopedListing()));
  }

  @Test
  void theTotalBehindThatListingKeepsItsJoinsAndItsBinds() {
    assertMatchesGolden(
        "scoped-listing-count", renderer.renderCount(ProductionPatterns.scopedListing()));
  }

  @Test
  void thePagedFormAddsOnlyOrderingAndAWindow() {
    assertMatchesGolden(
        "paged-scoped-listing", renderer.render(ProductionPatterns.pagedScopedListing()));
  }

  @Test
  void onePermissionViewJoinedTwiceRendersTwoIndependentlyBoundConditions() {
    assertMatchesGolden(
        "double-grant-self-join", renderer.render(ProductionPatterns.doubleGrantSelfJoin()));
  }

  @Test
  void aReceivedDisjunctionStaysInsideTheScopeItNarrows() {
    assertMatchesGolden(
        "scope-narrowed-by-search", renderer.render(ProductionPatterns.scopeNarrowedBySearch()));
  }

  @Test
  void aFilterArrivingAlreadyBuiltNarrowsTheScopeItWasGivenTo() {
    Condition received =
        new CriteriaAdapter(mappingContext)
            .toCondition(ProductionPatterns.receivedFilter(), ProductionPatterns.ACCOUNT)
            .orElseThrow();

    assertMatchesGolden(
        "external-filter",
        renderer.render(ProductionPatterns.scopeNarrowedByReceivedFilter(received)));
  }

  @Test
  void theUnsafeSortEscapeRendersItsFragmentWithTheColumnFilledIn() {
    assertMatchesGolden(
        "unsafe-sort-expression",
        renderer.render(ProductionPatterns.listingSortedByAnExpression()));
  }

  private void assertMatchesGolden(String scenario, RenderedStatement statement) {
    assertThat(goldenTextOf(statement))
        .describedAs(
            "rendered statement for '%s' — accept it by writing this text to"
                + " src/test/resources/%s/%s.sql",
            scenario, GOLDEN_DIRECTORY, scenario)
        .isEqualTo(recordedGolden(scenario));
  }

  // Assembled here rather than through RenderedStatement#previewWithValues: that preview is a
  // product surface whose format may change, and every golden would churn with it. '\n' rather than
  // the platform separator, so a golden compares the same on every OS.
  private static String goldenTextOf(RenderedStatement statement) {
    StringBuilder golden = new StringBuilder(statement.sql()).append('\n');

    for (Binding binding : statement.bindings()) {
      golden.append(binding.marker()).append(" = ").append(binding.value()).append('\n');
    }

    return golden.toString();
  }

  private static String recordedGolden(String scenario) {
    String resource = GOLDEN_DIRECTORY + "/" + scenario + ".sql";

    try (InputStream recorded =
             ProductionPatternGoldenSqlTest.class.getClassLoader().getResourceAsStream(resource)) {

      if (recorded == null) {
        throw new IllegalStateException("No golden file on the test classpath at " + resource);
      }

      return new String(recorded.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      throw new UncheckedIOException("Reading the golden file " + resource + " failed", unreadable);
    }
  }
}
