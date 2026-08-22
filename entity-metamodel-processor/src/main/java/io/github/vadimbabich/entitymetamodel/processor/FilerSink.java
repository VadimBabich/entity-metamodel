package io.github.vadimbabich.entitymetamodel.processor;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.vadimbabich.entitymetamodel.core.GeneratedSourceSink;
import java.io.IOException;
import java.io.OutputStream;
import javax.annotation.processing.Filer;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Writes generated sources through the {@code Filer}, naming exactly one originating element.
 *
 * <p>The element is a field rather than a varargs argument on purpose: naming zero or several is
 * not an error the compiler reports, it silently downgrades every consumer to full recompilation.
 * One sink per entity makes the invariant impossible to break rather than merely tested.
 */
final class FilerSink implements GeneratedSourceSink {

  private final Filer filer;
  private final ForeignTypes foreignTypes;
  private final Messager messager;
  private final Element originatingElement;

  FilerSink(
      Filer filer, ForeignTypes foreignTypes, Messager messager, Element originatingElement) {

    this.filer = filer;
    this.foreignTypes = foreignTypes;
    this.messager = messager;
    this.originatingElement = originatingElement;
  }

  @Override
  public void accept(String fullyQualifiedClassName, String sourceText) {
    if (foreignTypes.declares(fullyQualifiedClassName)) {
      messager.printMessage(
          Diagnostic.Kind.ERROR,
          "EM-E6: " + fullyQualifiedClassName + " already exists, so no metamodel can be generated"
              + " for " + originatingElement + "; rename the existing type or configure a different"
              + " naming strategy",
          originatingElement);

      return;
    }

    try {
      write(fullyQualifiedClassName, sourceText);
    } catch (FilerException e) {
      // Two causes, indistinguishable from here: another entity mapped to this name in the same
      // round, or a metamodel from an earlier build is among the inputs. Only the second cleans.
      messager.printMessage(
          Diagnostic.Kind.ERROR,
          "EM-E6: " + fullyQualifiedClassName + " cannot be written: " + e.getMessage()
              + ". Either two entities map to that name, or a metamodel from an earlier build is"
              + " among this compilation's inputs — the second is resolved by a clean build",
          originatingElement);
    } catch (IOException e) {
      messager.printMessage(
          Diagnostic.Kind.ERROR,
          "Cannot write " + fullyQualifiedClassName + ": " + e.getMessage(),
          originatingElement);
    }
  }

  private void write(String fullyQualifiedClassName, String sourceText) throws IOException {
    JavaFileObject sourceFile =
        filer.createSourceFile(fullyQualifiedClassName, originatingElement);

    // UTF-8 explicitly: the header is non-ASCII, and the platform default would make the generated
    // bytes depend on the machine that ran the build.
    try (OutputStream bytes = sourceFile.openOutputStream()) {
      bytes.write(sourceText.getBytes(UTF_8));
    }
  }
}
