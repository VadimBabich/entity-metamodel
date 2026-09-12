package io.github.vadimbabich.entitymetamodel.processor.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import org.junit.jupiter.api.Test;

/**
 * The processor copies the runtime's alias rules — reserved separator and forbidden suffix —
 * because the runtime is test-scope here. Drift does not fail the build; it ships a metamodel that
 * throws from its static initializer in the consumer's application.
 */
class ReservedSeparatorContractTest {

  @Test
  void theProcessorRefusesTheSeparatorTheRuntimeReserves() {
    assertThat(EntityAnalyzer.RESERVED_SEPARATOR).isEqualTo(EntityRef.PROJECTION_SEPARATOR);
  }

  @Test
  void theProcessorRefusesTheNameSuffixTheRuntimeRejectsAsAnAlias() {
    assertThat(Trailing_.class.getSimpleName()).endsWith(EntityAnalyzer.RESERVED_ALIAS_SUFFIX);

    assertThatIllegalArgumentException().isThrownBy(() -> EntityRef.of(Trailing_.class));
  }

  private static final class Trailing_ {
  }
}
