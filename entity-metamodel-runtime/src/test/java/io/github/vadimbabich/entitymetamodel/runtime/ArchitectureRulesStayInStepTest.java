package io.github.vadimbabich.entitymetamodel.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Both modules enforce the freeze with the same engine, duplicated because Maven's only way to
 * share test code across modules is a test-jar, and this family publishes what it builds.
 *
 * <p>Duplication is tolerable; silent divergence is not. Everything below the module-specific
 * constants must stay byte-identical, so a fix that reaches one suite and not the other fails here
 * instead of leaving a module enforcing a weaker rule.
 */
class ArchitectureRulesStayInStepTest {

  private static final String MAVEN_REACTOR_ROOT_PROPERTY = "maven.reactor.root";
  private static final Path MAVEN_REACTOR_ROOT =
      Path.of(System.getProperty(MAVEN_REACTOR_ROOT_PROPERTY, ".."));

  private static final Path VOCABULARY_SUITE =
      MAVEN_REACTOR_ROOT.resolve("entity-metamodel-runtime/src/test/java/io/github/vadimbabich"
          + "/entitymetamodel/runtime/ArchitectureRulesTest.java");

  private static final Path EXECUTION_SUITE =
      MAVEN_REACTOR_ROOT.resolve(
          "entity-metamodel-runtime-r2dbc/src/test/java/io/github/vadimbabich"
              + "/entitymetamodel/runtime/r2dbc/ArchitectureRulesTest.java");

  private static final String ENGINE_START =
      "  private static final Set<String> BLOCKING_CALLS";

  @Test
  void bothSuitesAreReadable() {
    assertThat(VOCABULARY_SUITE).as("vocabulary suite, resolved to %s — without it the comparison"
        + " below examines nothing and passes. Surefire supplies the reactor root as -D%s.",
        VOCABULARY_SUITE.toAbsolutePath(), MAVEN_REACTOR_ROOT_PROPERTY).exists();

    assertThat(EXECUTION_SUITE).as("execution suite, resolved to %s",
        EXECUTION_SUITE.toAbsolutePath()).exists();
  }

  @Test
  void theEnforcementEngineIsIdenticalInBothSuites() {
    String vocabularyEngine = enforcementEngineOf(VOCABULARY_SUITE);
    String executionEngine = enforcementEngineOf(EXECUTION_SUITE);

    assertThat(vocabularyEngine).as("the rules and predicates of %s and %s have diverged. Apply "
        + "the change to both suites, or extract the engine into a shared test artifact.",
        VOCABULARY_SUITE, EXECUTION_SUITE).isEqualTo(executionEngine);
  }

  private static String enforcementEngineOf(Path suite) {
    String source = readSource(suite);
    int engineStart = source.indexOf(ENGINE_START);

    assertThat(engineStart).as("'%s' marks where the shared engine begins in %s; without it this "
        + "test compares nothing", ENGINE_START, suite).isNotNegative();

    return source.substring(engineStart);
  }

  private static String readSource(Path suite) {
    try {
      return Files.readString(suite);
    } catch (IOException unreadable) {
      throw new UncheckedIOException("Cannot read " + suite.toAbsolutePath(), unreadable);
    }
  }
}
