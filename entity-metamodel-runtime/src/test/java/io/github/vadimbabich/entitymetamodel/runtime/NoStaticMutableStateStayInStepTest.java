package io.github.vadimbabich.entitymetamodel.runtime;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Holds the two modules' NoStaticMutableStateTest D3 sweeps in step; the tolerated duplication
 * and its limits are documented on {@link SourceRegionsStayInStep}.
 */
class NoStaticMutableStateStayInStepTest {

  private static final Path VOCABULARY_SWEEP = SourceRegionsStayInStep.reactorFile(
      "entity-metamodel-runtime/src/test/java/io/github/vadimbabich"
          + "/entitymetamodel/runtime/NoStaticMutableStateTest.java");

  private static final Path EXECUTION_SWEEP = SourceRegionsStayInStep.reactorFile(
      "entity-metamodel-runtime-r2dbc/src/test/java/io/github/vadimbabich"
          + "/entitymetamodel/runtime/r2dbc/NoStaticMutableStateTest.java");

  private static final String SWEEP_START =
      "  private static final List<Class<?>> IMMUTABLE_STATIC_TYPES";

  @Test
  void bothSweepsAreReadable() {
    SourceRegionsStayInStep.assertReadable(VOCABULARY_SWEEP, "vocabulary sweep");
    SourceRegionsStayInStep.assertReadable(EXECUTION_SWEEP, "execution sweep");
  }

  @Test
  void theSweepIsIdenticalInBothModules() {
    SourceRegionsStayInStep.assertIdenticalFrom(SWEEP_START, VOCABULARY_SWEEP, EXECUTION_SWEEP,
        "One module is now enforcing a weaker rule than the other. Apply the change to both "
            + "files, keeping everything from the IMMUTABLE_STATIC_TYPES declaration down "
            + "byte-identical.");
  }
}
