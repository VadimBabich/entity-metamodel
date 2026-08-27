package io.github.vadimbabich.entitymetamodel.processor.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import org.junit.jupiter.api.Test;

/**
 * The processor holds its own copy of the runtime's reserved alias separator, since the runtime is
 * test-scope here. Without this test the two can drift, and the failure is not a compile error but
 * a metamodel that throws from its static initializer in the consumer's application.
 */
class ReservedSeparatorContractTest {

  @Test
  void theProcessorRefusesTheSeparatorTheRuntimeReserves() {
    assertThat(EntityAnalyzer.RESERVED_SEPARATOR).isEqualTo(EntityRef.PROJECTION_SEPARATOR);
  }
}
