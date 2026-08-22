package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

@DisplayName("FluentSelect contract tests")
class FluentSelectContractTest {

  @Test
  @DisplayName("should create immutable builder for single-entity select")
  void singleEntitySelect() {
    EntityRef<Account> accountEntity = EntityRef.of(Account.class);

    FluentSelect<Account> select = FluentSelect.from(accountEntity);

    assertThat(select).isNotNull();
  }

  @Test
  @DisplayName("should build terminal statements")
  void terminals() {
    EntityRef<Account> accountEntity = EntityRef.of(Account.class);

    FluentSelect<Account> select = FluentSelect.from(accountEntity);

    assertThat(select.all()).isNotNull();
    assertThat(select.one()).isNotNull();
    assertThat(select.first()).isNotNull();
    assertThat(select.list()).isNotNull();
    assertThat(select.count()).isNotNull();
    assertThat(select.exists()).isNotNull();
  }

  @Test
  @DisplayName("builder is immutable — each step returns new instance")
  void builderImmutability() {
    EntityRef<Account> accountEntity = EntityRef.of(Account.class);
    FluentSelect<Account> original = FluentSelect.from(accountEntity);

    FluentSelect<Account> withWhereClause = original.where(
        (Condition) AccountRef.ACTIVE.is(true)
    );

    assertThat(original).isNotSameAs(withWhereClause);
  }

  // Test fixtures
  static class Account {
    public Long id;
    public String name;
    public Boolean active;
  }

  static class AccountRef {
    public static PropertyRef<Account, Long> ID = EntityRef.of(Account.class).property("id", Long.class);
    public static PropertyRef<Account, String> NAME = EntityRef.of(Account.class).property("name", String.class);
    public static PropertyRef<Account, Boolean> ACTIVE = EntityRef.of(Account.class).property("active", Boolean.class);
  }
}
