package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;

/**
 * Reading entities out of one row, keyed by instance rather than by class.
 *
 * <p>{@link #twoInstancesOfOneTableHydrateTheirOwnValues()} is the case the 1.x shape cannot do at
 * all: it reads by column name, so two projections of the same table give duplicate labels and the
 * second instance silently receives the first one's values.
 */
class ProjectedRowTest {

  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);
  private static final R2dbcMappingContext MAPPING_CONTEXT = new R2dbcMappingContext();

  private static ProjectedRow rowOf(Map<String, Object> columns) {
    return new ProjectedRow(columns, new MappingR2dbcConverter(MAPPING_CONTEXT));
  }

  private static Map<String, Object> columns(Object... labelsAndValues) {
    Map<String, Object> row = new LinkedHashMap<>();

    for (int pair = 0; pair < labelsAndValues.length; pair += 2) {
      row.put((String) labelsAndValues[pair], labelsAndValues[pair + 1]);
    }

    return row;
  }

  @Test
  void anInstanceIsHydratedFromItsOwnProjectedColumns() {
    ProjectedRow row =
        rowOf(columns("account__account_id", 7L, "account__owner_email", "owner@example.com"));

    Account account = row.read(ACCOUNT);

    assertThat(account.id).isEqualTo(7L);
    assertThat(account.ownerEmail).isEqualTo("owner@example.com");
  }

  @Test
  void twoInstancesOfOneTableHydrateTheirOwnValues() {
    EntityRef<Account> sponsor = ACCOUNT.as("sponsor");
    ProjectedRow row =
        rowOf(
            columns(
                "account__account_id", 1L,
                "account__owner_email", "first@example.com",
                "account_sponsor__account_id", 2L,
                "account_sponsor__owner_email", "second@example.com"));

    Account owner = row.read(ACCOUNT);
    Account sponsoring = row.read(sponsor);

    assertThat(owner.id).isEqualTo(1L);
    assertThat(owner.ownerEmail).isEqualTo("first@example.com");
    assertThat(sponsoring.id).isEqualTo(2L);
    assertThat(sponsoring.ownerEmail).isEqualTo("second@example.com");
  }

  @Test
  void anInstanceWhoseColumnsAreAllNullIsAbsentRatherThanBlank() {
    ProjectedRow row =
        rowOf(columns("account__account_id", null, "account__owner_email", null));

    assertThat(row.readOptional(ACCOUNT)).isEmpty();
  }

  @Test
  void readInsistsOnAnInstanceThatIsActuallyThere() {
    ProjectedRow row =
        rowOf(columns("account__account_id", null, "account__owner_email", null));

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> row.read(ACCOUNT))
        .withMessageContaining("readOptional");
  }

  @Test
  void anInstanceThatWasNeverProjectedIsRejected() {
    ProjectedRow row = rowOf(columns("membership__membership_id", 10L));

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> row.read(ACCOUNT))
        .withMessageContaining("account__");
  }
}
