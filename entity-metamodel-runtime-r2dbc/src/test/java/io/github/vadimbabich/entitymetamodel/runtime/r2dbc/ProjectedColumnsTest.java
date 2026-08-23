package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.relational.domain.RowDocument;

/**
 * Layer 3 of the naming rules: a row carries every projected instance's columns at once, so each
 * instance claims only the labels that begin with its own alias <em>and the separator</em>.
 *
 * <p>{@link #anAliasThatPrefixesAnotherDoesNotClaimItsColumns()} is the recorded anti-pattern, and
 * the reason the separator is part of the boundary: a bare alias comparison lets {@code account}
 * swallow {@code account_2}'s columns, which hydrates one instance from another's values and looks
 * entirely plausible in the result.
 */
class ProjectedColumnsTest {

  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);

  private static Map<String, Object> row(String... labelsAndValues) {
    Map<String, Object> columns = new LinkedHashMap<>();

    for (int pair = 0; pair < labelsAndValues.length; pair += 2) {
      columns.put(labelsAndValues[pair], labelsAndValues[pair + 1]);
    }

    return columns;
  }

  @Test
  void anInstanceClaimsItsOwnColumnsWithThePrefixRemoved() {
    RowDocument document =
        new ProjectedColumns(ACCOUNT)
            .documentFrom(row("account__account_id", "7", "account__owner_email", "owner@example.com"));

    assertThat(document)
        .containsEntry("account_id", "7")
        .containsEntry("owner_email", "owner@example.com")
        .hasSize(2);
  }

  @Test
  void anAliasThatPrefixesAnotherDoesNotClaimItsColumns() {
    EntityRef<Account> sponsor = ACCOUNT.as("2");
    Map<String, Object> bothInstances =
        row("account__account_id", "1", "account_2__account_id", "2");

    RowDocument defaultInstance = new ProjectedColumns(ACCOUNT).documentFrom(bothInstances);
    RowDocument secondInstance = new ProjectedColumns(sponsor).documentFrom(bothInstances);

    assertThat(defaultInstance).containsExactly(Map.entry("account_id", "1"));
    assertThat(secondInstance).containsExactly(Map.entry("account_id", "2"));
  }

  @Test
  void columnsBelongingToAnotherInstanceAreIgnored() {
    RowDocument document =
        new ProjectedColumns(ACCOUNT)
            .documentFrom(row("account__account_id", "7", "membership__membership_id", "10"));

    assertThat(document).containsExactly(Map.entry("account_id", "7"));
  }

  @Test
  void aRowCarryingNoneOfTheInstancesColumnsIsRejected() {
    // Not an empty result — a row that arrived without this instance's labels means the statement
    // and the hydration disagree, and returning a blank entity would hide that entirely.
    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(
            () -> new ProjectedColumns(ACCOUNT).documentFrom(row("membership__account_id", "1")))
        .withMessageContaining("account__");
  }
}
