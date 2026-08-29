package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The inspection door: a consumer can see what a statement will send before it sends it, and log it
 * wherever they choose — the library logs nothing itself.
 *
 * <p>Values are redacted unless asked for. A preview reaches a log far more often than it is read
 * once, so printing bind values by default would record whatever every filtered query filtered on.
 */
class StatementPreviewTest {

  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);

  private final QueryRenderer renderer = TestRenderers.postgres();

  private RenderedStatement filteredByEmail() {
    PropertyRef<Account, String> ownerEmail = ACCOUNT.property("ownerEmail", String.class);

    return renderer.render(FluentSelect.from(ACCOUNT).where(ownerEmail.is("owner@example.com")));
  }

  @Test
  void aPreviewShowsTheStatementAndHidesItsValues() {
    String preview = filteredByEmail().preview();

    assertThat(preview).contains("WHERE \"account\".\"owner_email\" = $1");
    assertThat(preview).contains("$1 = <redacted>");
    assertThat(preview).doesNotContain("owner@example.com");
  }

  @Test
  void valuesAppearOnlyWhenExplicitlyAskedFor() {
    String preview = filteredByEmail().previewWithValues();

    assertThat(preview).contains("$1 = owner@example.com");
  }

  @Test
  void aPreviewCarriesTheSameSqlThatWouldBeExecuted() {
    RenderedStatement statement = filteredByEmail();

    assertThat(statement.preview()).startsWith(statement.sql());
    assertThat(statement.previewWithValues()).startsWith(statement.sql());
  }

  @Test
  void everyMarkerIsListedInTheOrderItIsBound() {
    PropertyRef<Account, Long> id = ACCOUNT.property("id", Long.class);

    RenderedStatement statement =
        renderer.render(FluentSelect.from(ACCOUNT).where(id.in(List.of(7L, 8L))));

    assertThat(statement.previewWithValues()).containsSubsequence("$1 = 7", "$2 = 8");
  }

  @Test
  void aStatementWithoutValuesPreviewsAsJustTheStatement() {
    RenderedStatement statement = renderer.render(FluentSelect.from(ACCOUNT));

    assertThat(statement.preview()).isEqualTo(statement.sql());
  }
}
