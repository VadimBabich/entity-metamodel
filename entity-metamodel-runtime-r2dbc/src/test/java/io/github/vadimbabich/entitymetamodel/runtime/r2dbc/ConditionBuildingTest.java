package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Condition building tests")
class ConditionBuildingTest {

  static class Account {
    public Long id;
    public String email;
    public Boolean active;
  }

  @Test
  @DisplayName("should build a simple equality condition")
  void simpleEquality() {
    EntityRef<Account> entity = EntityRef.of(Account.class);
    PropertyRef<Account, String> email = entity.property("email", String.class);

    Condition condition = (Condition) email.is("user@example.com");

    assertThat(condition).isNotNull();
    assertThat(condition).isInstanceOf(Condition.class);
  }

  @Test
  @DisplayName("should build an IN condition")
  void inCondition() {
    EntityRef<Account> entity = EntityRef.of(Account.class);
    PropertyRef<Account, Long> id = entity.property("id", Long.class);

    Condition condition = (Condition) id.in(1L, 2L, 3L);

    assertThat(condition).isNotNull();
  }

  @Test
  @DisplayName("should build an IS NULL condition")
  void isNullCondition() {
    EntityRef<Account> entity = EntityRef.of(Account.class);
    PropertyRef<Account, String> email = entity.property("email", String.class);

    Condition condition = (Condition) email.isNull();

    assertThat(condition).isNotNull();
  }

  @Test
  @DisplayName("should compose conditions with AND")
  void composeWithAnd() {
    EntityRef<Account> entity = EntityRef.of(Account.class);
    PropertyRef<Account, String> email = entity.property("email", String.class);
    PropertyRef<Account, Boolean> active = entity.property("active", Boolean.class);

    Condition emailCondition = (Condition) email.is("user@example.com");
    Condition activeCondition = (Condition) active.is(true);
    Condition combined = emailCondition.and(activeCondition);

    assertThat(combined).isNotNull();
    assertThat(combined).isInstanceOf(Condition.class);
  }

  @Test
  @DisplayName("should compose conditions with OR")
  void composeWithOr() {
    EntityRef<Account> entity = EntityRef.of(Account.class);
    PropertyRef<Account, String> email = entity.property("email", String.class);
    PropertyRef<Account, Boolean> active = entity.property("active", Boolean.class);

    Condition emailCondition = (Condition) email.is("user@example.com");
    Condition activeCondition = (Condition) active.is(true);
    Condition combined = emailCondition.or(activeCondition);

    assertThat(combined).isNotNull();
    assertThat(combined).isInstanceOf(Condition.class);
  }

  @Test
  @DisplayName("should support comparison operators")
  void comparisonOperators() {
    EntityRef<Account> entity = EntityRef.of(Account.class);
    PropertyRef<Account, Long> id = entity.property("id", Long.class);

    Condition gt = (Condition) id.gt(100L);
    Condition gte = (Condition) id.gte(100L);
    Condition lt = (Condition) id.lt(100L);
    Condition lte = (Condition) id.lte(100L);

    assertThat(gt).isNotNull();
    assertThat(gte).isNotNull();
    assertThat(lt).isNotNull();
    assertThat(lte).isNotNull();
  }

  @Test
  @DisplayName("should support LIKE operator")
  void likeOperator() {
    EntityRef<Account> entity = EntityRef.of(Account.class);
    PropertyRef<Account, String> email = entity.property("email", String.class);

    Condition like = (Condition) email.like("%@example.com");

    assertThat(like).isNotNull();
  }
}
