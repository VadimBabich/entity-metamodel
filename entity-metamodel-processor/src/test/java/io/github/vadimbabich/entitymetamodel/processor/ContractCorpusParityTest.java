package io.github.vadimbabich.entitymetamodel.processor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.processor.testing.CompilationOutcome;
import io.github.vadimbabich.entitymetamodel.processor.testing.ContractCorpus;
import io.github.vadimbabich.entitymetamodel.processor.testing.FixtureCompiler;
import io.github.vadimbabich.entitymetamodel.processor.testing.MetamodelParity;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The acceptance gate: compiling the contract corpus with the processor reproduces the golden
 * metamodels byte for byte, and does so again on the next run.
 *
 * <p>They are compiled in the same round against the real runtime jar, so the output is valid Java
 * and not merely the right bytes.
 */
class ContractCorpusParityTest {

  private static final List<String> GOLDEN_METAMODELS = List.of(
      "Account__",
      "Inventory__",
      "LegacyDocument__",
      "Payment__",
      "Vendor__",
      "Wrapper_Attachment__");

  @TempDir
  Path workDirectory;

  @Test
  void everyGoldenMetamodelIsReproducedByteForByte() {
    CompilationOutcome outcome = compileCorpusIn(workDirectory);

    assertThat(outcome.errors()).isEmpty();
    assertThat(outcome.succeeded()).isTrue();

    for (String metamodel : GOLDEN_METAMODELS) {
      MetamodelParity.assertIdentical(
          metamodel,
          ContractCorpus.goldenBytes(metamodel),
          outcome.generatedSource(ContractCorpus.generatedPathOf(metamodel)));
    }
  }

  @Test
  void oneFileIsGeneratedPerEmissionRootAndNothingElse() {
    CompilationOutcome outcome = compileCorpusIn(workDirectory);

    assertThat(outcome.generatedSources().keySet())
        .containsExactlyInAnyOrderElementsOf(
            GOLDEN_METAMODELS.stream().map(ContractCorpus::generatedPathOf).toList());
  }

  @Test
  void theCorpusGeneratesWithoutDiagnostics() {
    CompilationOutcome outcome = compileCorpusIn(workDirectory);

    assertThat(outcome.errors()).isEmpty();
    assertThat(outcome.warnings()).isEmpty();
    assertThat(outcome.notes()).isEmpty();
  }

  @Test
  void generatingTwiceProducesIdenticalBytes() {
    CompilationOutcome first = compileCorpusIn(workDirectory.resolve("first"));
    CompilationOutcome second = compileCorpusIn(workDirectory.resolve("second"));

    for (String metamodel : GOLDEN_METAMODELS) {
      String path = ContractCorpus.generatedPathOf(metamodel);

      MetamodelParity.assertIdentical(
          metamodel, first.generatedSource(path), second.generatedSource(path));
    }
  }

  private CompilationOutcome compileCorpusIn(Path directory) {
    return new FixtureCompiler(directory)
        .compile(ContractCorpus.sources(), new EntityMetamodelProcessor());
  }
}
