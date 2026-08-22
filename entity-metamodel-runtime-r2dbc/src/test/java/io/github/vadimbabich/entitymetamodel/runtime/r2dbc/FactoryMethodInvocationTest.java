package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Factory method invocation tests")
class FactoryMethodInvocationTest {

  static class Account {
    public Long id;
    public String email;
  }

  @Test
  @DisplayName("should invoke factory build method for equal")
  void invokeBuildMethodForEqual() {
    try {
      EntityRef<Account> entity = EntityRef.of(Account.class);
      PropertyRef<Account, String> email = entity.property("email", String.class);

      ConditionFactory factory = ConditionFactory.getInstance();
      Object result = factory.build("equal", email, "test@example.com");

      assertThat(result).isNotNull();
      assertThat(result).isInstanceOf(Condition.class);
    } catch (Exception e) {
      fail("Failed to invoke factory method", e);
    }
  }

  @Test
  @DisplayName("should invoke factory build method for in")
  void invokeBuildMethodForIn() {
    try {
      EntityRef<Account> entity = EntityRef.of(Account.class);
      PropertyRef<Account, Long> id = entity.property("id", Long.class);

      ConditionFactory factory = ConditionFactory.getInstance();
      Object result = factory.build("in", id, new Object[]{1L, 2L, 3L});

      assertThat(result).isNotNull();
      assertThat(result).isInstanceOf(Condition.class);
    } catch (Exception e) {
      fail("Failed to invoke factory method", e);
    }
  }

  @Test
  @DisplayName("should reflectively invoke build method")
  void reflectivelyInvokeBuildMethod() {
    try {
      EntityRef<Account> entity = EntityRef.of(Account.class);
      PropertyRef<Account, String> email = entity.property("email", String.class);

      ClassLoader loader = Thread.currentThread().getContextClassLoader();
      Class<?> factoryClass =
          Class.forName("io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ConditionFactory", true, loader);
      Object factory = factoryClass.getMethod("getInstance").invoke(null);

      Object result = factoryClass.getMethod("build", String.class, PropertyRef.class, Object[].class)
          .invoke(factory, "equal", email, new Object[]{"test@example.com"});
      assertThat(result).isNotNull();
    } catch (Exception e) {
      fail("Failed to reflectively invoke factory method", e);
    }
  }
}
