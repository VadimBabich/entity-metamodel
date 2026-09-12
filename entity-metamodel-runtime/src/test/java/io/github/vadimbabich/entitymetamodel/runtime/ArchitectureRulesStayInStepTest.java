package io.github.vadimbabich.entitymetamodel.runtime;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Holds the two modules' ArchitectureRulesTest enforcement engines in step; the tolerated
 * duplication and its limits are documented on {@link SourceRegionsStayInStep}.
 */
class ArchitectureRulesStayInStepTest {

  private static final Path VOCABULARY_SUITE = SourceRegionsStayInStep.reactorFile(
      "entity-metamodel-runtime/src/test/java/io/github/vadimbabich"
          + "/entitymetamodel/runtime/ArchitectureRulesTest.java");

  private static final Path EXECUTION_SUITE = SourceRegionsStayInStep.reactorFile(
      "entity-metamodel-runtime-r2dbc/src/test/java/io/github/vadimbabich"
          + "/entitymetamodel/runtime/r2dbc/ArchitectureRulesTest.java");

  private static final String ENGINE_START =
      "  private static final Set<String> BLOCKING_CALLS";

  @Test
  void bothSuitesAreReadable() {
    SourceRegionsStayInStep.assertReadable(VOCABULARY_SUITE, "vocabulary suite");
    SourceRegionsStayInStep.assertReadable(EXECUTION_SUITE, "execution suite");
  }

  @Test
  void theEnforcementEngineIsIdenticalInBothSuites() {
    SourceRegionsStayInStep.assertIdenticalFrom(ENGINE_START, VOCABULARY_SUITE, EXECUTION_SUITE,
        "Apply the change to both suites, or extract the engine into a shared test artifact.");
  }
}
