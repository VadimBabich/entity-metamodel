package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Order;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Person;
import org.junit.jupiter.api.Test;

/**
 * Every identifier the statement mints is quoted, and every identifier the mapping context supplies
 * keeps the quoting the context chose.
 *
 * <p>Unquoted, an alias taken from a class named {@code Order} or {@code User} is a keyword and the
 * statement will not parse; a mixed-case column folds to lower case and stops existing. Both are
 * invisible to a fixture whose names happen to be safe.
 */
class IdentifierQuotingTest {

  private static final EntityRef<Order> ORDER = EntityRef.of(Order.class);

  private final QueryRenderer renderer = TestRenderers.postgres();

  @Test
  void anAliasTakenFromAReservedWordIsQuoted() {
    RenderedStatement statement = renderer.render(FluentSelect.from(ORDER));

    assertThat(statement.sql()).contains("FROM \"orders\" \"order\"");
  }

  @Test
  void aProjectedLabelIsQuotedSoItsCaseSurvives() {
    RenderedStatement statement = renderer.render(FluentSelect.from(ORDER));

    assertThat(statement.sql()).contains("\"order\".\"placedBy\" AS \"order__placedBy\"");
  }

  @Test
  void aFilteredColumnKeepsTheQuotingTheMappingContextChose() {
    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(ORDER).where(ORDER.property("placedBy", String.class).is("someone")));

    assertThat(statement.sql()).endsWith("WHERE \"order\".\"placedBy\" = $1");
  }

  @Test
  void anEntityWithAnEmbeddedValueIsRefusedRatherThanProjectedWithoutIt() {
    // An embedded value's properties are columns of this table. Skipping them would hydrate the
    // embedded field silently null, which a read-modify-write then writes back over the real data.
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> renderer.render(FluentSelect.from(EntityRef.of(Person.class))))
        .withMessageContaining("address")
        .withMessageContaining("embedded");
  }

  @Test
  void countingAndProbingAnEmbeddedBearingEntityAreAllowedBecauseNeitherHydratesIt() {
    FluentSelect<Person> people = FluentSelect.from(EntityRef.of(Person.class));

    // The refusal exists because hydration would drop the value. Neither of these hydrates
    // anything, and refusing them would remove a working capability for no safety gain.
    assertThat(renderer.renderCount(people).sql()).startsWith("SELECT COUNT(1)");
    assertThat(renderer.renderExistsProbe(people).sql()).startsWith("SELECT 1");
  }

  @Test
  void aSortedColumnKeepsItToo() {
    RenderedStatement statement =
        renderer.render(
            FluentSelect.from(ORDER).orderBy(ORDER.property("placedBy", String.class).asc()));

    assertThat(statement.sql()).endsWith("ORDER BY \"order\".\"placedBy\" ASC");
  }
}
