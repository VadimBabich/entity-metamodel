package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.Comparison;
import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.Inclusion;
import io.github.vadimbabich.entitymetamodel.runtime.Junction;
import io.github.vadimbabich.entitymetamodel.runtime.NullCheck;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
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
import org.springframework.data.relational.core.sql.Functions;
import org.springframework.data.relational.core.sql.OrderByField;
import org.springframework.data.relational.core.sql.SQL;
import org.springframework.data.relational.core.sql.Select;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.data.relational.core.sql.SelectBuilder;
import org.springframework.data.relational.core.sql.StatementBuilder;
import org.springframework.data.relational.core.sql.Table;
import org.springframework.data.relational.core.sql.render.SqlRenderer;
import org.springframework.r2dbc.core.binding.BindMarker;
import org.springframework.r2dbc.core.binding.BindMarkers;
import org.springframework.r2dbc.core.binding.BindMarkersFactory;

/**
 * Renders a {@link FluentSelect} description into SQL and its ordered bind values. Rendering is a
 * pure function of the description and touches no connection.
 *
 * <p>Spring's SQL AST types are spelled out in full below rather than imported: they must never
 * appear in a public signature, and the package at every use is the reminder that they stop here.
 *
 * <p>Pass the application's configured mapping context. A bare
 * {@code new RelationalMappingContext()} knows no dialect simple types and will disagree with the
 * context the executor binds against.
 */
public final class QueryRenderer {

  private final RelationalMappingContext mappingContext;
  private final BindMarkersFactory bindMarkersFactory;
  private final SqlRenderer sqlRenderer;

  public QueryRenderer(RelationalMappingContext mappingContext, R2dbcDialect dialect) {
    Objects.requireNonNull(dialect, "dialect");

    this.mappingContext = Objects.requireNonNull(mappingContext, "mappingContext");
    this.bindMarkersFactory = dialect.getBindMarkersFactory();

    // The dialect's context, not the bare renderer: the bare one emits ANSI
    // 'OFFSET n ROWS FETCH FIRST m ROWS ONLY', which MySQL rejects.
    this.sqlRenderer = SqlRenderer.create(new RenderContextFactory(dialect).createRenderContext());
  }

  public RenderedStatement render(FluentSelect<?> select) {
    Objects.requireNonNull(select, "select");

    RenderPass pass = new RenderPass(tablesOf(select), bindMarkersFactory.create());
    Select statement = buildStatement(select, projectionOf(select, pass), pass);

    return new RenderedStatement(sqlRenderer.render(statement), pass.bindings());
  }

  /**
   * The total behind a page: the same FROM, joins and filter, projected as a count. Sort and paging
   * come off the description rather than being skipped, so the count cannot drift from its page.
   */
  public RenderedStatement renderCount(FluentSelect<?> select) {
    Objects.requireNonNull(select, "select");

    FluentSelect<?> wholeFilteredSet = select.withoutSortAndPaging();
    RenderPass pass = new RenderPass(tablesOf(wholeFilteredSet), bindMarkersFactory.create());
    List<Expression> countProjection = List.of(Functions.count(SQL.literalOf(1)));

    Select statement = buildStatement(wholeFilteredSet, countProjection, pass);

    return new RenderedStatement(sqlRenderer.render(statement), pass.bindings());
  }

  /**
   * Whether anything matches, as a single row of a literal. Like a count it projects no entity
   * column, so it neither needs nor pays for the selected entity's projection.
   *
   * <p>Any offset is kept, because {@code exists(page.offset(end))} is how "is there another page"
   * is asked; dropping it would answer "does anything match" and report a next page forever.
   */
  public RenderedStatement renderExistsProbe(FluentSelect<?> select) {
    Objects.requireNonNull(select, "select");

    FluentSelect<?> onePage = select.withoutSort().limit(1);
    RenderPass pass = new RenderPass(tablesOf(onePage), bindMarkersFactory.create());

    Select statement = buildStatement(onePage, List.of(SQL.literalOf(1)), pass);

    return new RenderedStatement(sqlRenderer.render(statement), pass.bindings());
  }

  /** Keyed by ref identity, which is what makes two instances of one table distinguishable. */
  private Map<EntityRef<?>, Table> tablesOf(FluentSelect<?> select) {
    Map<EntityRef<?>, Table> tables = new LinkedHashMap<>();
    Map<String, EntityRef<?>> byAlias = new LinkedHashMap<>();

    addTable(select.entity(), tables, byAlias);
    for (TableJoin<?> join : select.joins()) {
      addTable(join.targetInstance(), tables, byAlias);
    }

    return tables;
  }

  /**
   * Refs are distinct per (type, alias) but SQL knows only the alias, so two entity types sharing a
   * simple name reach here as different instances carrying one alias. Caught here rather than left
   * to the driver, which reports a duplicate table name and names neither type.
   */
  private void addTable(
      EntityRef<?> instance, Map<EntityRef<?>, Table> tables, Map<String, EntityRef<?>> byAlias) {

    EntityRef<?> claimed = byAlias.putIfAbsent(instance.alias(), instance);
    if (claimed != null && !claimed.equals(instance)) {
      throw new IllegalArgumentException(
          instance.entityType().getName() + " and " + claimed.entityType().getName()
              + " both use the alias '" + instance.alias() + "'; give one of them a distinct"
              + " instance with EntityRef.as(...)");
    }

    tables.put(instance, tableFor(instance));
  }

  private Table tableFor(EntityRef<?> instance) {
    RelationalPersistentEntity<?> persistentEntity =
        mappingContext.getRequiredPersistentEntity(instance.entityType());

    // Quoted: an alias derived from a class named Order or User is a keyword, and unquoted it
    // makes the statement unparseable rather than merely odd.
    return Table.create(persistentEntity.getTableName())
        .as(SqlIdentifier.quoted(instance.alias()));
  }

  private Select buildStatement(
      FluentSelect<?> select, List<Expression> projection, RenderPass pass) {

    // Here rather than while projecting, so a count and its page accept the same descriptions —
    // a count builds no projection and would otherwise skip the check.
    for (EntityRef<?> alsoSelected : select.alsoSelected()) {
      pass.tableOf(alsoSelected);
    }

    SelectBuilder.SelectFromAndJoin fromClause =
        StatementBuilder.select(projection).from(pass.tableOf(select.entity()));

    // Paging is applied before the joins and the filter because the staged builder offers limit and
    // offset only on the from/join stages — SelectWhereAndOr has neither. The renderer still places
    // them last in the SQL, so do not "correct" these calls into SQL order.
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

    List<TableJoin<?>> joins = select.joins();
    if (joins.isEmpty()) {
      return stage;
    }

    SelectBuilder.SelectFromAndJoinCondition joinedClause = applyJoin(stage, joins.get(0), pass);
    for (TableJoin<?> furtherJoin : joins.subList(1, joins.size())) {
      joinedClause = applyJoin(joinedClause, furtherJoin, pass);
    }

    return joinedClause;
  }

  /** Re-anchoring the target column to the joined instance is how one relationship reaches N. */
  private <T> SelectBuilder.SelectFromAndJoinCondition applyJoin(
      SelectBuilder.SelectJoin stage, TableJoin<T> join, RenderPass pass) {

    Column sourceColumn = columnFor(join.relationship().source(), pass);
    Column targetColumn =
        columnFor(join.relationship().target().of(join.targetInstance()), pass);

    org.springframework.data.relational.core.sql.Condition onCondition =
        Conditions.isEqual(sourceColumn, targetColumn);
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
      OrderByField undirected = OrderByField.from(columnFor(sortOrder.property(), pass));

      fields.add(directed(undirected, sortOrder.direction()));
    }

    return fields;
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

  /**
   * A relationship is skipped — its value lives in the referenced table. An embedded value is
   * refused, because the opposite is true of it and projecting nothing would hydrate the field as
   * null with no error anywhere. Both report {@code isEntity()}, so the embedded check comes first.
   */
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

      columns.add(
          table
              .column(columnName)
              .as(SqlIdentifier.quoted(instance.projectedLabel(columnName.getReference()))));
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
    if (condition instanceof NullCheck nullCheck) {
      return Conditions.isNull(columnFor(nullCheck.property(), pass));
    }
    if (condition instanceof Junction junction) {
      return translateJunction(junction, pass);
    }
    if (condition instanceof SqlExpr rawFragment) {
      return translateRawFragment(rawFragment, pass);
    }

    throw new IllegalStateException(
        "No rendering for condition variant " + condition.getClass().getName()
            + ": the sealed hierarchy grew and this renderer did not follow");
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

    // Both operands are nested. Left un-grouped, SQL's AND-over-OR precedence re-associates the
    // clause and an OR operand escapes its junction, widening the match with no error anywhere.
    // Do not "simplify" this by nesting only the OR side: which side needs grouping depends on the
    // parent, which a recursive translation cannot see.
    org.springframework.data.relational.core.sql.Condition left =
        Conditions.nest(translate(junction.left(), pass));
    org.springframework.data.relational.core.sql.Condition right =
        Conditions.nest(translate(junction.right(), pass));

    return switch (junction.operator()) {
      case AND -> left.and(right);
      case OR -> left.or(right);
    };
  }

  /**
   * Only the markers are written into the fragment text; the values go through the bind path, which
   * is what keeps a value that looks like SQL from becoming SQL.
   */
  private org.springframework.data.relational.core.sql.Condition translateRawFragment(
      SqlExpr rawFragment, RenderPass pass) {

    String[] literalParts = rawFragment.sql().split("\\?", -1);
    StringBuilder assembled = new StringBuilder(literalParts[0]);

    for (int placeholder = 0; placeholder < rawFragment.bindings().size(); placeholder++) {
      String markerText = pass.bindPlaceholder(rawFragment.bindings().get(placeholder));

      assembled.append(markerText).append(literalParts[placeholder + 1]);
    }

    return Conditions.just(assembled.toString());
  }

  private Column columnFor(PropertyRef<?, ?> property, RenderPass pass) {
    return pass.tableOf(property.entity()).column(columnIdentifierFor(property));
  }

  /**
   * Resolved twice on purpose. {@link PropertyRef#columnName} owns the rules about what counts as a
   * column and reports why when a property does not, but its {@code String} result cannot carry the
   * quoting the context chose — and dropping that folds a mixed-case column out of existence.
   */
  private SqlIdentifier columnIdentifierFor(PropertyRef<?, ?> property) {
    property.columnName(mappingContext);

    return mappingContext
        .getRequiredPersistentEntity(property.entity().entityType())
        .getRequiredPersistentProperty(property.name())
        .getColumnName();
  }

  /**
   * One pass over one statement. It owns the statement's only {@link BindMarkers} and accumulates
   * values in allocation order, so marker {@code n} and binding {@code n} cannot drift apart.
   */
  private static final class RenderPass {

    private final Map<EntityRef<?>, Table> tables;
    private final BindMarkers markers;
    private final List<Binding> bindings = new ArrayList<>();

    RenderPass(Map<EntityRef<?>, Table> tables, BindMarkers markers) {
      this.tables = tables;
      this.markers = markers;
    }

    /**
     * Refuses an instance the statement never named. Falling back to the selected table would
     * attribute one table's column to another and return plausible rows from the wrong side.
     */
    Table tableOf(EntityRef<?> instance) {
      Table table = tables.get(instance);

      if (table == null) {
        throw new IllegalArgumentException(
            instance + " is not part of this statement; select from that instance or join it"
                + " before naming its properties");
      }

      return table;
    }

    /** Returns the marker's own placeholder text rather than an AST node's {@code toString()}. */
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
