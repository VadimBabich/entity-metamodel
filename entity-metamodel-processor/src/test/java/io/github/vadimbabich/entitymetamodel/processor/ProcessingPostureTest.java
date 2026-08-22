package io.github.vadimbabich.entitymetamodel.processor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.processor.testing.CompilationOutcome;
import io.github.vadimbabich.entitymetamodel.processor.testing.ContractCorpus;
import io.github.vadimbabich.entitymetamodel.processor.testing.FixtureCompiler;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the processor deliberately does not do, witnessed so nobody "fixes" it.
 *
 * <p>{@code @Table} belongs to Spring, so {@code process} returns {@code false} and leaves it
 * visible to the rest of the build. The price is recorded below: under {@code -Xlint:processing}
 * javac notes that nothing claimed it. Claiming it to silence the note would take the annotation
 * away from every other processor, so the note stays.
 */
class ProcessingPostureTest {

  @TempDir
  Path workDirectory;

  @Test
  void theMappingAnnotationsAreLeftUnclaimedEvenThoughJavacRemarksOnIt() {
    CompilationOutcome outcome = new FixtureCompiler(workDirectory)
        .compile(ContractCorpus.sources(), new EntityMetamodelProcessor(), "-Xlint:processing");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.warnings())
        .anyMatch(warning -> warning.contains("No processor claimed any of these annotations")
            && warning.contains("org.springframework.data.relational.core.mapping.Table"));
  }

  @Test
  void thereIsNothingElseForJavacToComplainAboutInTheGeneratedSources() {
    CompilationOutcome outcome = new FixtureCompiler(workDirectory)
        .compile(ContractCorpus.sources(), new EntityMetamodelProcessor(),
            "-Xlint:all", "-Xlint:-processing");

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.warnings()).isEmpty();
  }
}
