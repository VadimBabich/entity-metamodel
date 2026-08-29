package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.GRANT;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.MEMBERSHIP;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.ACCOUNT;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.PRINCIPAL_ID;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.SPONSOR;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.AccessGrant;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Membership;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Properties that hold by construction rather than by check, asserted anyway because a construction
 * can be refactored away. Each failure is silent: wrong rows or wrong values, never an error, and
 * never visible in the query that caused them.
 */
class StatementInvariantsTest {

  private static final Pattern PROJECTED_LABEL = Pattern.compile("AS \"([^\"]+)\"");
  private static final String WHERE_KEYWORD = " WHERE ";

  private final QueryRenderer renderer = TestRenderers.postgres();

  /**
   * A composition that unions the bind values but keeps only one side's condition leaves a
   * statement asking for fewer predicates than it binds. Where the dropped one scopes rows to a
   * principal, that is a dropped permission check.
   */
  @Test
  void twoIndependentlyBoundJoinConditionsBothSurviveComposition() {
    RenderedStatement statement = renderer.render(ProductionPatterns.doubleGrantSelfJoin());

    assertThat(statement.sql())
        .contains("\"accessgrant\".\"principal_id\" = $1")
        .contains("\"accessgrant\".\"has_browse_access\" = $2")
        .contains("\"accessgrant_licence\".\"principal_id\" = $3")
        .contains("\"accessgrant_licence\".\"has_browse_access\" = $4");
    assertThat(statement.values()).containsExactly(PRINCIPAL_ID, true, PRINCIPAL_ID, true);
  }

  @Test
  void anAndCarriesBothOperandsWhicheverWayRoundItWasComposed() {
    PropertyRef<AccessGrant, Long> principalId = GRANT.property("principalId", Long.class);
    PropertyRef<AccessGrant, Boolean> browseAccess =
        GRANT.property("hasBrowseAccess", Boolean.class);

    RenderedStatement principalFirst =
        renderer.render(grantsWhere(principalId.is(PRINCIPAL_ID).and(browseAccess.is(true))));
    RenderedStatement accessFirst =
        renderer.render(grantsWhere(browseAccess.is(true).and(principalId.is(PRINCIPAL_ID))));

    // The whole clause, not a substring of the statement: every one of these columns is also
    // projected, so `sql().contains("principal_id")` passes on a statement whose WHERE lost it.
    assertThat(whereClauseOf(principalFirst))
        .isEqualTo(
            "(\"accessgrant\".\"principal_id\" = $1)"
                + " AND (\"accessgrant\".\"has_browse_access\" = $2)");
    assertThat(whereClauseOf(accessFirst))
        .isEqualTo(
            "(\"accessgrant\".\"has_browse_access\" = $1)"
                + " AND (\"accessgrant\".\"principal_id\" = $2)");
    assertThat(principalFirst.values()).containsExactly(PRINCIPAL_ID, true);
    assertThat(accessFirst.values()).containsExactly(true, PRINCIPAL_ID);
  }

  /**
   * Resolve a projection by entity type instead of by instance and the second instance of one table
   * reuses the first's labels, so reading the row returns the first instance's values for both.
   */
  @Test
  void noTwoInstancesOfOneStatementProjectTheSameLabel() {
    List<String> labels = projectedLabelsOf(renderer.render(ProductionPatterns.scopedListing()));

    assertThat(labels).hasSize(13).doesNotHaveDuplicates();
  }

  /**
   * Cache resolution on a shared helper under a key that omits part of what was resolved, and a
   * statement renders differently depending on which preceded it — a defect no single query can
   * reproduce.
   */
  @Test
  void aStatementRendersTheSameWhateverWasRenderedBeforeIt() {
    RenderedStatement firstRendering = renderer.render(ProductionPatterns.scopedListing());

    renderer.render(ProductionPatterns.doubleGrantSelfJoin());
    renderer.render(FluentSelect.from(MEMBERSHIP));

    RenderedStatement laterRendering = renderer.render(ProductionPatterns.scopedListing());

    assertThat(laterRendering.sql()).isEqualTo(firstRendering.sql());
    assertThat(laterRendering.values()).isEqualTo(firstRendering.values());
  }

  /**
   * If the key for an entity's columns omits the prefix they were built with, whichever statement
   * ran first decides the labels and the second reads its rows under names it never asked for.
   */
  @Test
  void anInstanceIsProjectedUnderItsOwnStatementsAlias() {
    RenderedStatement asSponsor = renderer.render(FluentSelect.from(SPONSOR));
    RenderedStatement asAccount = renderer.render(FluentSelect.from(ACCOUNT));

    assertThat(asSponsor.sql()).contains("AS \"account_sponsor__owner_email\"");
    assertThat(asAccount.sql())
        .contains("AS \"account__owner_email\"")
        .doesNotContain("account_sponsor__");
  }

  /**
   * In a builder that is a mutable state machine, what a description means depends on what has
   * already been asked of it. Here it is a value, so deriving from it changes nothing.
   */
  @Test
  void derivingATotalAndAPageLeavesTheDescriptionUnchanged() {
    FluentSelect<Membership> listing = ProductionPatterns.scopedListing();
    String beforeDeriving = renderer.render(listing).sql();

    renderer.renderCount(listing);
    renderer.render(ProductionPatterns.pagedScopedListing());
    renderer.renderExistsProbe(listing);

    assertThat(renderer.render(listing).sql()).isEqualTo(beforeDeriving);
  }

  /**
   * Where a condition is a callback over a shared bind context instead of data, it can only be
   * attached once, in the order the context expects.
   */
  @Test
  void oneConditionValueServesTwoStatementsWithMarkersOfTheirOwn() {
    Condition grantedToPrincipal =
        GRANT.property("principalId", Long.class).is(PRINCIPAL_ID);

    RenderedStatement onItsOwn = renderer.render(grantsWhere(grantedToPrincipal));
    RenderedStatement alongsideAnother =
        renderer.render(
            grantsWhere(
                GRANT.property("hasBrowseAccess", Boolean.class)
                    .is(true)
                    .and(grantedToPrincipal)));

    assertThat(onItsOwn.values()).containsExactly(PRINCIPAL_ID);
    assertThat(alongsideAnother.values()).containsExactly(true, PRINCIPAL_ID);
    assertThat(alongsideAnother.bindings().get(0).marker()).isEqualTo("$1");
  }

  private static String whereClauseOf(RenderedStatement statement) {
    String sql = statement.sql();

    return sql.substring(sql.indexOf(WHERE_KEYWORD) + WHERE_KEYWORD.length());
  }

  private static FluentSelect<AccessGrant> grantsWhere(Condition condition) {
    return FluentSelect.from(GRANT).where(condition);
  }

  private static List<String> projectedLabelsOf(RenderedStatement statement) {
    Matcher labels = PROJECTED_LABEL.matcher(statement.sql());

    List<String> found = new ArrayList<>();
    while (labels.find()) {
      found.add(labels.group(1));
    }

    return found;
  }
}
