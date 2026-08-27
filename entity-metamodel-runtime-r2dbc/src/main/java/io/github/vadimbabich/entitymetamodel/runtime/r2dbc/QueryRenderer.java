package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.Comparison;
import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.ExpressionSort;
import io.github.vadimbabich.entitymetamodel.runtime.Inclusion;
import io.github.vadimbabich.entitymetamodel.runtime.Junction;
import io.github.vadimbabich.entitymetamodel.runtime.Negation;
import io.github.vadimbabich.entitymetamodel.runtime.NullCheck;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyEquality;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import io.github.vadimbabich.entitymetamodel.runtime.PropertySort;
import io.github.vadimbabich.entitymetamodel.runtime.SortOrder;
import io.github.vadimbabich.entitymetamodel.runtime.SqlExpr;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.data.r2dbc.dialect.R2dbcDialect;
import org.springframework.data.relational.core.dialect.RenderContextFactory;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;
import org.springframework.data.relational.core.sql.Column;
import org.springframework.data.relational.core.sql.Conditions;
import org.springframework.data.relational.core.sql.Expression;
import org.springframework.data.relational.core.sql.Expressions;
import org.springframework.data.relational.core.sql.Functions;
import org.springframework.data.relational.core.sql.IdentifierProcessing;
import org.springframework.data.relational.core.sql.OrderByField;
import org.springframework.data.relational.core.sql.SQL;
import org.springframework.data.relational.core.sql.Select;
import org.springframework.data.relational.core.sql.SelectBuilder;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.data.relational.core.sql.StatementBuilder;
import org.springframework.data.relational.core.sql.Table;
import org.springframework.data.relational.core.sql.render.SqlRenderer;
import org.springframework.r2dbc.core.binding.BindMarker;
import org.springframework.r2dbc.core.binding.BindMarkers;
import org.springframework.r2dbc.core.binding.BindMarkersFactory;

/**
 * Renders a {@link FluentSelect} description into SQL and its ordered bind values, as a pure
 * function of the description.
 *
 * <p>Pass the application's configured mapping context: a bare one knows no dialect simple types
 * and will disagree with the context the executor binds against. Spring's SQL AST types are spelled
 * out in full below rather than imported, as a reminder that they stop here.
 */
public final class QueryRenderer {

  private final RelationalMappingContext mappingContext;
  private final AliasScheme aliasScheme;
  private final BindMarkersFactory bindMarkersFactory;
  private final IdentifierProcessing identifierProcessing;
  private final SqlRenderer sqlRenderer;

  public QueryRenderer(RelationalMappingContext mappingContext, R2dbcDialect dialect) {
    Objects.requireNonNull(dialect, "dialect");

    this.mappingContext = Objects.requireNonNull(mappingContext, "mappingContext");
    this.aliasScheme = new AliasScheme(this.mappingContext);
    this.bindMarkersFactory = dialect.getBindMarkersFactory();
    this.identifierProcessing = dialect.getIdentifierProcessing();

    // The dialect's context, not the bare renderer: the bare one emits ANSI
    // 'OFFSET n ROWS FETCH FIRST m ROWS ONLY', which MySQL rejects.
    this.sqlRenderer = SqlRenderer.create(new RenderContextFactory(dialect).createRenderContext());
  }

  // Shared with the executor's name resolution: a name this renderer cannot resolve must not be a
  // name something else accepted.
  RelationalMappingContext mappingContext() {
    return mappingContext;
  }

  public RenderedStatement render(FluentSelect<?> select) {
    Objects.requireNonNull(select, "select");

    RenderPass pass = passOver(select);
    Select statement = buildStatement(select, projectionOf(select, pass), pass);

    return statementOf(statement, pass);
  }

  /**
   * The total behind a page: the same FROM, joins and filter, projected as a count. Sort and paging
   * come off the description, so the count cannot drift from its page.
   */
  public RenderedStatement renderCount(FluentSelect<?> select) {
    Objects.requireNonNull(select, "select");

    FluentSelect<?> wholeFilteredSet = select.withoutSortAndPaging();
    RenderPass pass = passOver(wholeFilteredSet);
    List<Expression> countProjection = List.of(Functions.count(SQL.literalOf(1)));

    Select statement = buildStatement(wholeFilteredSet, countProjection, pass);

    return statementOf(statement, pass);
  }

  /**
   * Whether anything matches, as a single row of a literal — no entity column projected. The offset
   * is kept: {@code exists(page.offset(end))} is how "is there another page" is asked.
   */
  public RenderedStatement renderExistsProbe(FluentSelect<?> select) {
    Objects.requireNonNull(select, "select");

    FluentSelect<?> onePage = select.withoutSort().withAtMostOneRow();
    RenderPass pass = passOver(onePage);

    Select statement = buildStatement(onePage, List.of(SQL.literalOf(1)), pass);

    return statementOf(statement, pass);
  }

  // A pattern switch would make these unreachable by construction, but needs Java 21 and this
  // module compiles at release 17 where that syntax is preview only. Revisit if the floor rises.
  private static IllegalStateException unhandledVariant(String kind, Object variant) {
    return new IllegalStateException(
        "No rendering for " + kind + " variant " + variant.getClass().getName()
            + ": the sealed hierarchy grew and this renderer did not follow");
  }

  private RenderedStatement statementOf(Select statement, RenderPass pass) {
    return new RenderedStatement(sqlRenderer.render(statement), pass.bindings(), pass.aliases());
  }

  private RenderPass passOver(FluentSelect<?> select) {
    List<EntityRef<?>> instances = instancesOf(select);
    StatementAliases aliases = aliasScheme.forInstances(instances);

    Map<EntityRef<?>, Table> tables = new LinkedHashMap<>();
    for (EntityRef<?> instance : instances) {
      tables.put(instance, tableFor(instance, aliases));
    }

    return new RenderPass(tables, aliases, bindMarkersFactory.create());
  }

  // First-reference order, which is what makes a positional alias assignment deterministic. Refs
  // are distinct per (type, alias) but SQL knows only the alias, so two entity types sharing a
  // simple name claim one alias — caught here, because the driver would name neither type.
  private List<EntityRef<?>> instancesOf(FluentSelect<?> select) {
    Map<String, EntityRef<?>> byAlias = new LinkedHashMap<>();

    List<EntityRef<?>> instances = select.instances();

    for (EntityRef<?> instance : instances) {
      EntityRef<?> claimed = byAlias.putIfAbsent(instance.alias(), instance);

      if (claimed != null && !claimed.equals(instance)) {
        throw new IllegalArgumentException(
            instance.entityType().getName() + " and " + claimed.entityType().getName()
                + " both use the alias '" + instance.alias() + "'; give one of them a distinct"
                + " instance with EntityRef.as(...)");
      }
    }

    return instances;
  }

  private Table tableFor(EntityRef<?> instance, StatementAliases aliases) {
    RelationalPersistentEntity<?> persistentEntity =
        mappingContext.getRequiredPersistentEntity(instance.entityType());

    // Quoted: an alias derived from a class named Order or User is a keyword, and unquoted it
    // makes the statement unparseable rather than merely odd.
    return Table.create(persistentEntity.getTableName())
        .as(SqlIdentifier.quoted(aliases.aliasOf(instance)));
  }

  private Select buildStatement(
      FluentSelect<?> select, List<Expression> projection, RenderPass pass) {

    // Here rather than while projecting, so a count accepts the same descriptions as its page.
    for (EntityRef<?> alsoSelected : select.alsoSelected()) {
      pass.tableOf(alsoSelected);
    }

    SelectBuilder.SelectFromAndJoin fromClause =
        StatementBuilder.select(projection).from(pass.tableOf(select.entity()));

    // Before the joins and the filter because the staged builder offers limit and offset only on
    // the from/join stages. The SQL still places them last, so do not "correct" this into SQL
    // order.
    SelectBuilder.SelectFromAndJoin pagedClause = applyPaging(fromClause, select);

    return applyWhereAndSort(applyJoins(pagedClause, select, pass), select, pass);
  }

  private SelectBuilder.SelectFromAndJoin applyPaging(
      SelectBuilder.SelectFromAndJoin stage, FluentSelect<?> select) {

    SelectBuilder.SelectFromAndJoin pagedStage = stage;

    OptionalLong limit = select.limit();
    if (limit.isPresent()) {
      pagedStage = pagedStage.limit(limit.getAsLong());
    }

    OptionalLong offset = select.offset();
    if (offset.isPresent()) {
      pagedStage = pagedStage.offset(offset.getAsLong());
    }

    return pagedStage;
  }

  private SelectBuilder.SelectWhere applyJoins(
      SelectBuilder.SelectFromAndJoin stage, FluentSelect<?> select, RenderPass pass) {

    List<TableJoin> joins = select.joins();
    if (joins.isEmpty()) {
      return stage;
    }

    SelectBuilder.SelectFromAndJoinCondition joinedClause = applyJoin(stage, joins.get(0), pass);
    for (TableJoin furtherJoin : joins.subList(1, joins.size())) {
      joinedClause = applyJoin(joinedClause, furtherJoin, pass);
    }

    return joinedClause;
  }

  private SelectBuilder.SelectFromAndJoinCondition applyJoin(
      SelectBuilder.SelectJoin stage, TableJoin join, RenderPass pass) {

    org.springframework.data.relational.core.sql.Condition onCondition =
        translate(join.onCondition(), pass);
    Table joinedTable = pass.tableOf(join.targetInstance());

    return switch (join.kind()) {
      case INNER -> stage.join(joinedTable).on(onCondition);
      case LEFT_OUTER -> stage.leftOuterJoin(joinedTable).on(onCondition);
    };
  }

  private Select applyWhereAndSort(
      SelectBuilder.SelectWhere stage, FluentSelect<?> select, RenderPass pass) {

    SelectBuilder.SelectOrdered filteredStage = stage;

    Optional<Condition> whereCondition = select.whereCondition();
    if (whereCondition.isPresent()) {
      filteredStage = stage.where(translate(whereCondition.get(), pass));
    }

    List<SortOrder> sortOrders = select.sortOrders();
    if (sortOrders.isEmpty()) {
      return filteredStage.build();
    }

    return filteredStage.orderBy(orderByFieldsOf(sortOrders, pass)).build();
  }

  private List<OrderByField> orderByFieldsOf(List<SortOrder> sortOrders, RenderPass pass) {
    List<OrderByField> fields = new ArrayList<>();

    for (SortOrder sortOrder : sortOrders) {
      OrderByField undirected = OrderByField.from(sortExpressionOf(sortOrder, pass));

      fields.add(directed(undirected, sortOrder.direction()));
    }

    return fields;
  }

  private Expression sortExpressionOf(SortOrder sortOrder, RenderPass pass) {
    if (sortOrder instanceof PropertySort propertySort) {
      return columnFor(propertySort.property(), pass);
    }
    if (sortOrder instanceof ExpressionSort expressionSort) {
      return Expressions.just(assembleFragment(expressionSort.expression(), pass));
    }

    throw unhandledVariant("sort", sortOrder);
  }

  private static OrderByField directed(OrderByField field, SortOrder.Direction direction) {
    return switch (direction) {
      case ASCENDING -> field.asc();
      case DESCENDING -> field.desc();
    };
  }

  private List<Expression> projectionOf(FluentSelect<?> select, RenderPass pass) {
    List<Expression> projection = new ArrayList<>(columnsOf(select.entity(), pass));

    for (EntityRef<?> alsoSelected : select.alsoSelected()) {
      projection.addAll(columnsOf(alsoSelected, pass));
    }

    return projection;
  }

  // A relationship is skipped: its value lives in the referenced table. An embedded value is
  // refused, since projecting nothing would hydrate the field as null with no error. Both report
  // isEntity(), so the embedded check comes first.
  private List<Expression> columnsOf(EntityRef<?> instance, RenderPass pass) {
    RelationalPersistentEntity<?> persistentEntity =
        mappingContext.getRequiredPersistentEntity(instance.entityType());
    Table table = pass.tableOf(instance);

    List<Expression> columns = new ArrayList<>();

    for (RelationalPersistentProperty property : persistentEntity) {
      if (property.isEmbedded()) {
        throw new IllegalArgumentException(
            instance + " declares the embedded property '" + property.getName()
                + "', whose columns this renderer does not yet project; hydrating it would return"
                + " an entity with that value silently absent");
      }
      if (property.isEntity()) {
        continue;
      }

      SqlIdentifier columnName = property.getColumnName();
      String label = pass.projectedLabel(instance, columnName.getReference());

      // Here rather than where the alias scheme is chosen: an instance joined only to filter emits
      // no label, so measuring it would refuse a statement that renders correctly.
      aliasScheme.rejectUnrenderableLabel(
          label, instance.entityType().getSimpleName() + "." + property.getName());

      columns.add(table.column(columnName).as(SqlIdentifier.quoted(label)));
    }

    return columns;
  }

  private org.springframework.data.relational.core.sql.Condition translate(
      Condition condition, RenderPass pass) {

    if (condition instanceof Comparison comparison) {
      return translateComparison(comparison, pass);
    }
    if (condition instanceof Inclusion inclusion) {
      return translateInclusion(inclusion, pass);
    }
    if (condition instanceof PropertyEquality propertyEquality) {
      return Conditions.isEqual(
          columnFor(propertyEquality.left(), pass), columnFor(propertyEquality.right(), pass));
    }
    if (condition instanceof NullCheck nullCheck) {
      return Conditions.isNull(columnFor(nullCheck.property(), pass));
    }
    if (condition instanceof Junction junction) {
      return translateJunction(junction, pass);
    }
    if (condition instanceof Negation negation) {
      return translateNegation(negation, pass);
    }
    if (condition instanceof SqlExpr rawFragment) {
      return translateRawFragment(rawFragment, pass);
    }

    throw unhandledVariant("condition", condition);
  }

  private org.springframework.data.relational.core.sql.Condition translateComparison(
      Comparison comparison, RenderPass pass) {

    Column column = columnFor(comparison.property(), pass);
    Expression value = pass.bind(comparison.value());

    return switch (comparison.operator()) {
      case EQUAL -> Conditions.isEqual(column, value);
      case GREATER_THAN -> Conditions.isGreater(column, value);
      case GREATER_THAN_OR_EQUAL -> Conditions.isGreaterOrEqualTo(column, value);
      case LESS_THAN -> Conditions.isLess(column, value);
      case LESS_THAN_OR_EQUAL -> Conditions.isLessOrEqualTo(column, value);
      case LIKE -> Conditions.like(column, value);
    };
  }

  private org.springframework.data.relational.core.sql.Condition translateInclusion(
      Inclusion inclusion, RenderPass pass) {

    List<Expression> markers = new ArrayList<>();
    for (Object value : inclusion.values()) {
      markers.add(pass.bind(value));
    }

    return Conditions.in(columnFor(inclusion.property(), pass), markers);
  }

  private org.springframework.data.relational.core.sql.Condition translateJunction(
      Junction junction, RenderPass pass) {

    // Both operands, always. Un-grouped, AND-over-OR precedence lets an OR operand escape its
    // junction and widen the match silently. Do not "simplify" to nesting only the OR side: which
    // side needs grouping depends on the parent, which a recursive translation cannot see.
    org.springframework.data.relational.core.sql.Condition left =
        Conditions.nest(translate(junction.left(), pass));
    org.springframework.data.relational.core.sql.Condition right =
        Conditions.nest(translate(junction.right(), pass));

    return switch (junction.operator()) {
      case AND -> left.and(right);
      case OR -> left.or(right);
    };
  }

  // Nested for the same reason a junction's operands are: NOT binds tighter than AND, so an
  // un-grouped conjunction underneath would be negated in its first term only.
  private org.springframework.data.relational.core.sql.Condition translateNegation(
      Negation negation, RenderPass pass) {

    return Conditions.not(Conditions.nest(translate(negation.condition(), pass)));
  }

  // Only markers reach the fragment text; values go through the bind path, which is what keeps a
  // value that looks like SQL from becoming SQL.
  private org.springframework.data.relational.core.sql.Condition translateRawFragment(
      SqlExpr rawFragment, RenderPass pass) {

    return Conditions.just(assembleFragment(rawFragment, pass));
  }

  private String assembleFragment(SqlExpr rawFragment, RenderPass pass) {
    String[] literalParts = rawFragment.sql().split("\\?", -1);
    StringBuilder assembled = new StringBuilder(literalParts[0]);

    List<Object> arguments = rawFragment.arguments();
    for (int placeholder = 0; placeholder < arguments.size(); placeholder++) {
      assembled
          .append(placeholderTextFor(arguments.get(placeholder), pass))
          .append(literalParts[placeholder + 1]);
    }

    return assembled.toString();
  }

  // A property becomes the instance's qualified column, anything else a marker. Rendering the
  // column here rather than letting the caller write the alias is what keeps a fragment working
  // when the statement's aliases change, and it quotes the way the projection does.
  private String placeholderTextFor(Object argument, RenderPass pass) {
    if (argument instanceof PropertyRef<?, ?> property) {
      // For its rejection of an instance the statement never named, which would otherwise reach the
      // database as valid-looking SQL.
      pass.tableOf(property.entity());

      String tableAlias =
          SqlIdentifier.quoted(pass.aliasOf(property.entity())).toSql(identifierProcessing);

      return tableAlias + "." + columnIdentifierFor(property).toSql(identifierProcessing);
    }

    return pass.bindPlaceholder(argument);
  }

  private Column columnFor(PropertyRef<?, ?> property, RenderPass pass) {
    return pass.tableOf(property.entity()).column(columnIdentifierFor(property));
  }

  // Resolved twice on purpose: PropertyRef#columnName owns the rules about what counts as a column
  // and reports why when one does not, but its String result cannot carry the quoting the context
  // chose, and dropping that folds a mixed-case column out of existence.
  private SqlIdentifier columnIdentifierFor(PropertyRef<?, ?> property) {
    property.columnName(mappingContext);

    return mappingContext
        .getRequiredPersistentEntity(property.entity().entityType())
        .getRequiredPersistentProperty(property.name())
        .getColumnName();
  }

  // Owns the statement's only BindMarkers and accumulates values in allocation order, so marker n
  // and binding n cannot drift apart.
  private static final class RenderPass {

    private final Map<EntityRef<?>, Table> tables;
    private final StatementAliases aliases;
    private final BindMarkers markers;
    private final List<Binding> bindings = new ArrayList<>();

    RenderPass(
        Map<EntityRef<?>, Table> tables, StatementAliases aliases, BindMarkers markers) {

      this.tables = tables;
      this.aliases = aliases;
      this.markers = markers;
    }

    StatementAliases aliases() {
      return aliases;
    }

    String aliasOf(EntityRef<?> instance) {
      return aliases.aliasOf(instance);
    }

    String projectedLabel(EntityRef<?> instance, String columnName) {
      return aliases.projectedLabel(instance, columnName);
    }

    // Falling back to the selected table would attribute one table's column to another and return
    // plausible rows from the wrong side.
    Table tableOf(EntityRef<?> instance) {
      Table table = tables.get(instance);

      if (table == null) {
        throw new IllegalArgumentException(
            instance + " is not part of this statement; select from that instance or join it"
                + " before naming its properties");
      }

      return table;
    }

    // The marker's own placeholder text, not an AST node's toString().
    String bindPlaceholder(Object value) {
      BindMarker marker = markers.next();
      String placeholder = marker.getPlaceholder();

      bindings.add(new Binding(placeholder, value));

      return placeholder;
    }

    Expression bind(Object value) {
      return SQL.bindMarker(bindPlaceholder(value));
    }

    List<Binding> bindings() {
      return List.copyOf(bindings);
    }
  }
}
