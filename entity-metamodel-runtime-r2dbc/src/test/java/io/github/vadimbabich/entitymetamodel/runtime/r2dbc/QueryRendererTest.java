package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.ACCOUNT;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.SPONSOR;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.accountId;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.ownerEmail;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.BlankSchemaDocument;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.BlankSidedNameDocument;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.DerivedNameDocument;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.DotInNameDocument;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.DottedSchemaDocument;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.SchemaDocument;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.ThreePartNameDocument;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.TrailingDotDocument;

import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;
import org.springframework.data.relational.core.mapping.NamingStrategy;
import org.springframework.data.relational.core.sql.SqlIdentifier;

/**
 * Rendering is a pure function of the description: no connection, no database, no Spring context.
 * Names appear quoted because the statement renders through the dialect's render context.
 *
 * <p>The load-bearing case is
 * {@link #anOrOperandIsParenthesisedSoItCannotEscapeASurroundingAnd()}: an ungrouped OR lets
 * AND-over-OR precedence widen the match silently, so the grouping is pinned by exact SQL.
 */
class QueryRendererTest {

  private static final String DOTTED_FROM =
      "FROM \"app_schema\".\"v_document_meta\" \"dottedschemadocument\"";
  private static final String THREE_PART_FROM =
      "FROM \"catalog.app_schema.v_document_meta\" \"threepartnamedocument\"";
  private static final String DOT_IN_NAME_FROM =
      "FROM \"app_schema\".\"weird.name\" \"dotinnamedocument\"";
  private static final String BLANK_SCHEMA_FROM =
      "FROM \"app_schema\".\"v_document_meta\" \"blankschemadocument\"";

  private final QueryRenderer renderer = TestRenderers.postgres();

  @Test
  void aSelectWithoutAWhereClauseProjectsEveryColumnAgainstTheInstanceAlias() {
    RenderedStatement statement = renderer.render(FluentSelect.from(ACCOUNT));

    assertThat(statement.sql())
        .isEqualTo(
            "SELECT \"account\".\"account_id\" AS \"account__account_id\","
                + " \"account\".\"owner_email\" AS \"account__owner_email\","
                + " \"account\".\"state\" AS \"account__state\""
                + " FROM \"accounts\" \"account\"");
    assertThat(statement.values()).isEmpty();
  }

  @Test
  void aComparisonBindsItsValueRatherThanInliningIt() {
    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(ownerEmail().is("owner@example.com")));

    assertThat(statement.sql()).endsWith("WHERE \"account\".\"owner_email\" = $1");
    assertThat(statement.values()).containsExactly("owner@example.com");
  }

  @Test
  void anInclusionBindsOneMarkerPerValue() {
    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(accountId().in(List.of(1L, 2L, 3L))));

    assertThat(statement.sql()).endsWith("WHERE \"account\".\"account_id\" IN ($1, $2, $3)");
    assertThat(statement.values()).containsExactly(1L, 2L, 3L);
  }

  @Test
  void aNullCheckBindsNothing() {
    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(ownerEmail().isNull()));

    assertThat(statement.sql()).endsWith("WHERE \"account\".\"owner_email\" IS NULL");
    assertThat(statement.values()).isEmpty();
  }

  @Test
  void markersAreAllocatedInTheOrderTheirValuesAreBound() {
    Condition emailThenId = ownerEmail().is("owner@example.com").and(accountId().gt(10L));

    RenderedStatement statement = renderer.render(FluentSelect.from(ACCOUNT).where(emailThenId));

    assertThat(statement.sql())
        .endsWith("WHERE (\"account\".\"owner_email\" = $1) AND (\"account\".\"account_id\" > $2)");
    assertThat(statement.values()).containsExactly("owner@example.com", 10L);
  }

  @Test
  void anOrOperandIsParenthesisedSoItCannotEscapeASurroundingAnd() {
    Condition eitherEmail =
        ownerEmail().is("first@example.com").or(ownerEmail().is("second@example.com"));
    Condition activeAndEitherEmail = accountId().gt(0L).and(eitherEmail);

    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(activeAndEitherEmail));

    assertThat(statement.sql())
        .endsWith(
            "WHERE (\"account\".\"account_id\" > $1)"
                + " AND ((\"account\".\"owner_email\" = $2)"
                + " OR (\"account\".\"owner_email\" = $3))");
  }

  @Test
  void aNegatedConditionIsGroupedSoTheNotCoversAllOfIt() {
    Condition eitherEmail =
        ownerEmail().is("first@example.com").or(ownerEmail().is("second@example.com"));

    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(eitherEmail.not()));

    assertThat(statement.sql())
        .endsWith(
            "WHERE NOT ((\"account\".\"owner_email\" = $1)"
                + " OR (\"account\".\"owner_email\" = $2))");
    assertThat(statement.values()).containsExactly("first@example.com", "second@example.com");
  }

  @Test
  void aNegatedConditionComposedIntoAJunctionKeepsBothGroupings() {
    Condition activeAndNotExcluded = accountId().gt(0L).and(accountId().in(List.of(7L, 8L)).not());

    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(activeAndNotExcluded));

    assertThat(statement.sql())
        .endsWith(
            "WHERE (\"account\".\"account_id\" > $1)"
                + " AND (NOT (\"account\".\"account_id\" IN ($2, $3)))");
    assertThat(statement.values()).containsExactly(0L, 7L, 8L);
  }

  @Test
  void aSecondInstanceOfTheSameTableRendersUnderItsOwnAlias() {
    RenderedStatement statement =
        renderer.render(FluentSelect.from(SPONSOR).where(accountId().of(SPONSOR).is(7L)));

    assertThat(statement.sql())
        .isEqualTo(
            "SELECT \"account_sponsor\".\"account_id\" AS \"account_sponsor__account_id\","
                + " \"account_sponsor\".\"owner_email\" AS \"account_sponsor__owner_email\","
                + " \"account_sponsor\".\"state\" AS \"account_sponsor__state\""
                + " FROM \"accounts\" \"account_sponsor\""
                + " WHERE \"account_sponsor\".\"account_id\" = $1");
  }

  @Test
  void aSchemaQualifiedTableRendersSchemaAndTableAsSeparateIdentifiers() {
    RenderedStatement statement =
        renderer.render(FluentSelect.from(EntityRef.of(SchemaDocument.class)));

    assertThat(statement.sql())
        .contains("FROM \"app_schema\".\"v_document_meta\" \"schemadocument\"");
  }

  @Test
  void aDottedTableNameIsSplitIntoSchemaAndTable() {
    RenderedStatement statement =
        renderer.render(FluentSelect.from(EntityRef.of(DottedSchemaDocument.class)));

    assertThat(statement.sql())
        .contains(DOTTED_FROM);
  }

  @Test
  void aNameTheSplitCannotGroupIsLeftForTheSubstrateRatherThanGuessedAt() {
    RenderedStatement threeParts =
        renderer.render(FluentSelect.from(EntityRef.of(ThreePartNameDocument.class)));
    RenderedStatement emptySide =
        renderer.render(FluentSelect.from(EntityRef.of(TrailingDotDocument.class)));
    RenderedStatement blankSide =
        renderer.render(FluentSelect.from(EntityRef.of(BlankSidedNameDocument.class)));

    assertThat(threeParts.sql())
        .contains(THREE_PART_FROM);
    assertThat(emptySide.sql()).contains("FROM \"trailingdot.\" \"trailingdotdocument\"");
    assertThat(blankSide.sql()).contains("FROM \"app_schema. \" \"blanksidednamedocument\"");
  }

  @Test
  void aNameTheSplitCannotGroupStillOutranksTheStrategyDefaultSchema() {
    QueryRenderer qualifying = rendererWithDefaultSchema();

    RenderedStatement threeParts =
        qualifying.render(FluentSelect.from(EntityRef.of(ThreePartNameDocument.class)));

    assertThat(threeParts.sql())
        .contains(THREE_PART_FROM);
  }

  @Test
  void anUnquotedMultiPartNameReachesTheDatabaseAsTheDatabaseSpellsIt() {
    R2dbcMappingContext unquoting = new R2dbcMappingContext();
    unquoting.setForceQuote(false);

    QueryRenderer bare = TestRenderers.postgres(unquoting);

    RenderedStatement statement =
        bare.render(FluentSelect.from(EntityRef.of(ThreePartNameDocument.class)));

    assertThat(statement.sql())
        .contains("FROM catalog.app_schema.v_document_meta \"threepartnamedocument\"");
  }

  @Test
  void anExplicitSchemaLeavesADotInTheTableNameAlone() {
    RenderedStatement statement =
        renderer.render(FluentSelect.from(EntityRef.of(DotInNameDocument.class)));

    assertThat(statement.sql())
        .contains(DOT_IN_NAME_FROM);
  }

  @Test
  void splittingADerivedNameKeepsTheContextsQuotingPolicy() {
    R2dbcMappingContext schemaPrefixing = new R2dbcMappingContext(new SchemaPrefixingNames());
    schemaPrefixing.setForceQuote(false);

    QueryRenderer bare = TestRenderers.postgres(schemaPrefixing);

    RenderedStatement statement =
        bare.render(FluentSelect.from(EntityRef.of(DerivedNameDocument.class)));

    assertThat(statement.sql())
        .contains("FROM app_schema.derivednamedocument \"derivednamedocument\"");
  }

  @Test
  void aDefaultSchemaFromTheNamingStrategyQualifiesAnEntityThatDeclaresNone() {
    QueryRenderer qualifying = rendererWithDefaultSchema();

    RenderedStatement statement = qualifying.render(FluentSelect.from(ACCOUNT));

    assertThat(statement.sql()).contains("FROM \"tenant_a\".\"accounts\" \"account\"");
  }

  @Test
  void onlyAnIdentifierCarryingANameOfItsOwnIsSplit() {
    SqlIdentifier leaf = SqlIdentifier.quoted("app_schema.v_document_meta");
    SqlIdentifier twoParts =
        SqlIdentifier.from(SqlIdentifier.quoted("app_schema"), SqlIdentifier.quoted("v_doc"));
    SqlIdentifier onePartComposite = SqlIdentifier.from(SqlIdentifier.quoted("weird.name"));

    assertThat(TableNameResolver.carriesItsOwnName(leaf)).isTrue();
    assertThat(TableNameResolver.carriesItsOwnName(twoParts)).isFalse();
    assertThat(TableNameResolver.carriesItsOwnName(onePartComposite)).isFalse();
    assertThat(TableNameResolver.carriesItsOwnName(SqlIdentifier.EMPTY)).isFalse();
  }

  @Test
  void aDottedNameKeepsItsOwnSchemaRatherThanStackingTheStrategyDefaultOnTop() {
    QueryRenderer qualifying = rendererWithDefaultSchema();

    RenderedStatement dotted =
        qualifying.render(FluentSelect.from(EntityRef.of(DottedSchemaDocument.class)));
    RenderedStatement declared =
        qualifying.render(FluentSelect.from(EntityRef.of(DotInNameDocument.class)));

    assertThat(dotted.sql())
        .contains(DOTTED_FROM);
    assertThat(declared.sql())
        .contains(DOT_IN_NAME_FROM);
  }

  @Test
  void aBlankSchemaAttributeCountsAsAbsentHereBecauseItDoesInTheMappingContext() {
    RenderedStatement withoutDefault =
        renderer.render(FluentSelect.from(EntityRef.of(BlankSchemaDocument.class)));

    QueryRenderer qualifying = rendererWithDefaultSchema();
    RenderedStatement withDefault =
        qualifying.render(FluentSelect.from(EntityRef.of(BlankSchemaDocument.class)));

    assertThat(withoutDefault.sql())
        .contains(BLANK_SCHEMA_FROM);
    assertThat(withDefault.sql())
        .contains(BLANK_SCHEMA_FROM);
  }

  @Test
  void splittingADerivedNameKeepsTheDialectsLetterCaseStandardization() {
    QueryRenderer standardizing =
        TestRenderers.postgres(new R2dbcMappingContext(new MixedCaseSchemaPrefixingNames()));

    RenderedStatement statement =
        standardizing.render(FluentSelect.from(EntityRef.of(DerivedNameDocument.class)));

    assertThat(statement.sql())
        .contains("FROM \"app_schema\".\"derivednamedocument\" \"derivednamedocument\"");
  }

  @Test
  void renderingTheSameDescriptionTwiceProducesTheSameStatement() {
    FluentSelect<Account> select =
        FluentSelect.from(ACCOUNT).where(ownerEmail().is("owner@example.com"));

    RenderedStatement first = renderer.render(select);
    RenderedStatement second = renderer.render(select);

    assertThat(first.sql()).isEqualTo(second.sql());
    assertThat(first.values()).isEqualTo(second.values());
  }

  private static QueryRenderer rendererWithDefaultSchema() {
    return TestRenderers.postgres(new R2dbcMappingContext(new DefaultSchemaNames()));
  }

  @NullMarked
  private static final class SchemaPrefixingNames implements NamingStrategy {
    @Override
    public String getTableName(Class<?> type) {
      return "app_schema." + type.getSimpleName().toLowerCase(Locale.ROOT);
    }
  }

  @NullMarked
  private static final class MixedCaseSchemaPrefixingNames implements NamingStrategy {
    @Override
    public String getTableName(Class<?> type) {
      return "App_Schema." + type.getSimpleName();
    }
  }

  @NullMarked
  private static final class DefaultSchemaNames implements NamingStrategy {
    @Override
    public String getSchema() {
      return "tenant_a";
    }
  }
}
