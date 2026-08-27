package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.relational.domain.RowDocument;

/**
 * A row carries every projected instance's columns at once, so each instance claims only the labels
 * beginning with its own alias <em>and the separator</em> — a bare alias comparison lets
 * {@code account} swallow {@code account_2}'s columns and look plausible.
 */
class ProjectedColumnsTest {

  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);

  /**
   * Asked of the type that composes prefixes, so a change to that composition reaches here.
   */
  private static String prefixOf(EntityRef<?> instance) {
    return StatementAliases.declared(List.of(instance)).projectedLabelPrefix(instance);
  }

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
        ProjectedColumns.documentFrom(
            prefixOf(ACCOUNT),
            row("account__account_id", "7", "account__owner_email", "owner@example.com"));

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

    RowDocument defaultInstance =
        ProjectedColumns.documentFrom(prefixOf(ACCOUNT), bothInstances);
    RowDocument secondInstance =
        ProjectedColumns.documentFrom(prefixOf(sponsor), bothInstances);

    assertThat(defaultInstance).containsExactly(Map.entry("account_id", "1"));
    assertThat(secondInstance).containsExactly(Map.entry("account_id", "2"));
  }

  @Test
  void columnsBelongingToAnotherInstanceAreIgnored() {
    RowDocument document =
        ProjectedColumns.documentFrom(
            prefixOf(ACCOUNT),
            row("account__account_id", "7", "membership__membership_id", "10"));

    assertThat(document).containsExactly(Map.entry("account_id", "7"));
  }

  @Test
  void aRowCarryingNoneOfTheInstancesColumnsIsRejected() {
    // Not an empty result: the statement and the hydration disagree, and a blank entity hides it.
    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(
            () ->
                ProjectedColumns.documentFrom(
                    prefixOf(ACCOUNT), row("membership__account_id", "1")))
        .withMessageContaining("account__");
  }
}
