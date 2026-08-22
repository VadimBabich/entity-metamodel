package io.github.vadimbabich.entitymetamodel.processor;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.processor.analysis.MappingAnnotations;
import io.github.vadimbabich.entitymetamodel.processor.testing.CompilationOutcome;
import io.github.vadimbabich.entitymetamodel.processor.testing.FixtureCompiler;
import io.github.vadimbabich.entitymetamodel.processor.testing.Fixtures;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The originating-element invariant, at the only place it can be broken.
 *
 * <p>The compiler will not complain: naming zero or several originating elements leaves the code
 * correct and silently turns a consumer's incremental build into a full one, so the alarm has to
 * be here. The sink runs inside a real round, because the invariant is about what javac receives.
 */
class FilerSinkTest {

  @TempDir
  Path workDirectory;

  @Test
  void everyGeneratedFileNamesExactlyOneOriginatingElement() {
    RecordingFiler filer = runSinkOverOneEntity();

    assertThat(filer.created()).containsExactly("com.example.diagnostics.Probe__");
    assertThat(filer.originatingElements()).hasSize(1);
  }

  @Test
  void theSourceIsWrittenAsUtf8() {
    RecordingFiler filer = runSinkOverOneEntity();

    assertThat(filer.content()).isEqualTo("// en dash – kept\n");
  }

  private RecordingFiler runSinkOverOneEntity() {
    SinkProbe probe = new SinkProbe();

    CompilationOutcome outcome = new FixtureCompiler(workDirectory).compile(
        Fixtures.sources(Fixtures.diagnosticsSource("HealthyEntity.java")), probe);

    assertThat(outcome.errors()).isEmpty();
    return probe.filer;
  }

  /**
   * Drives one sink over the first entity of a real compilation. Re-declaring the supported
   * annotations is not optional — they are not inherited, and a processor declaring none is never
   * invoked.
   */
  @SupportedAnnotationTypes(MappingAnnotations.TABLE)
  private static final class SinkProbe extends AbstractProcessor {

    private final RecordingFiler filer = new RecordingFiler();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
      for (TypeElement annotation : annotations) {
        for (Element entity : round.getElementsAnnotatedWith(annotation)) {
          new FilerSink(filer, name -> false, processingEnv.getMessager(), entity)
              .accept("com.example.diagnostics.Probe__", "// en dash – kept\n");
        }
      }

      return false;
    }
  }

  private static final class RecordingFiler implements Filer {

    private final ByteArrayOutputStream content = new ByteArrayOutputStream();
    private List<String> created = List.of();
    private List<Element> originatingElements = List.of();

    @Override
    public JavaFileObject createSourceFile(CharSequence name, Element... originating) {
      created = List.of(name.toString());
      originatingElements = List.of(originating);

      return new InMemorySourceFile(name.toString(), content);
    }

    @Override
    public JavaFileObject createClassFile(CharSequence name, Element... originating) {
      throw new UnsupportedOperationException("The processor generates sources only");
    }

    @Override
    public FileObject createResource(
        JavaFileManager.Location location,
        CharSequence moduleAndPkg,
        CharSequence relativeName,
        Element... originating) {

      throw new UnsupportedOperationException("The processor generates sources only");
    }

    @Override
    public FileObject getResource(
        JavaFileManager.Location location, CharSequence moduleAndPkg, CharSequence relativeName) {

      throw new UnsupportedOperationException("The processor reads no resources");
    }

    List<String> created() {
      return created;
    }

    List<Element> originatingElements() {
      return originatingElements;
    }

    String content() {
      return content.toString(UTF_8);
    }
  }

  private static final class InMemorySourceFile extends SimpleJavaFileObject {

    private final OutputStream bytes;

    private InMemorySourceFile(String name, OutputStream bytes) {
      super(URI.create("mem:///" + name.replace('.', '/') + ".java"), Kind.SOURCE);
      this.bytes = bytes;
    }

    @Override
    public OutputStream openOutputStream() {
      return bytes;
    }
  }
}
