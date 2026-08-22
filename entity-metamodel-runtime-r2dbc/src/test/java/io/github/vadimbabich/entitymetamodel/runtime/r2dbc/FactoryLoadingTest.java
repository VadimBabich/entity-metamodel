package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Factory loading tests")
class FactoryLoadingTest {

  @Test
  @DisplayName("should load ConditionFactory via reflection")
  void loadFactory() {
    try {
      ClassLoader loader = Thread.currentThread().getContextClassLoader();
      Class<?> factoryClass =
          Class.forName("io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ConditionFactory", true, loader);
      assertThat(factoryClass).isNotNull();

      Object factory = factoryClass.getMethod("getInstance").invoke(null);
      assertThat(factory).isNotNull();
    } catch (Exception e) {
      fail("Failed to load factory via reflection", e);
    }
  }

  @Test
  @DisplayName("should create ConditionFactory directly")
  void createFactory() {
    ConditionFactory factory = ConditionFactory.getInstance();
    assertThat(factory).isNotNull();
  }
}
