package io.github.vadimbabich.entitymetamodel.processor;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import javax.annotation.processing.Processor;
import org.junit.jupiter.api.Test;

/**
 * The two registration files, checked against the class they name.
 *
 * <p>Both are strings in a resource, so a rename breaks them silently: the service file failing
 * means the processor is never invoked, the Gradle file failing means it is invoked but treated as
 * non-incremental — and that one is invisible, just slower consumer builds.
 *
 * <p>The option and annotation names below stay literal. Asserting them against the constants that
 * produce them compares each value with itself, so a rename would pass here and break consumers.
 */
class ProcessorRegistrationTest {

  private static final String PROCESSOR = EntityMetamodelProcessor.class.getName();

  @Test
  void theProcessorIsDiscoverableThroughTheServiceLoader() {
    Stream<String> discovered =
        ServiceLoader.load(Processor.class).stream().map(provider -> provider.type().getName());

    assertThat(discovered).contains(PROCESSOR);
  }

  @Test
  void theProcessorDeclaresItselfIsolatingForIncrementalCompilation() {
    assertThat(incrementalRegistration()).isEqualTo(PROCESSOR + ",isolating\n");
  }

  @Test
  void theOnlySupportedOptionIsTheStrictnessFlag() {
    assertThat(new EntityMetamodelProcessor().getSupportedOptions())
        .containsExactly("entitymetamodel.requireColumnAnnotation");
  }

  @Test
  void theProcessorSupportsTheAnnotationItDiscoversEntitiesBy() {
    assertThat(new EntityMetamodelProcessor().getSupportedAnnotationTypes())
        .containsExactly("org.springframework.data.relational.core.mapping.Table");
  }

  private String incrementalRegistration() {
    String resource = "/META-INF/gradle/incremental.annotation.processors";

    try (InputStream bytes = ProcessorRegistrationTest.class.getResourceAsStream(resource)) {
      if (bytes == null) {
        throw new AssertionError("Registration resource is missing: " + resource);
      }

      return new String(bytes.readAllBytes(), UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + resource, e);
    }
  }
}
