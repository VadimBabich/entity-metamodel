package io.github.vadimbabich.entitymetamodel.processor.testing;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;

/**
 * What one compilation round produced, in a form no particular harness owns.
 *
 * <p>Sources are bytes keyed by path relative to the source root, so one assertion works whether
 * they came from a {@code -s} directory, a {@code JavaFileObject} or anything else.
 */
public record CompilationOutcome(
    boolean succeeded,
    List<CompilationOutcome.Message> diagnostics,
    Map<String, byte[]> generatedSources) {

  public record Message(Diagnostic.Kind kind, String text) {
  }

  public byte[] generatedSource(String relativePath) {
    byte[] bytes = generatedSources.get(relativePath);

    if (bytes == null) {
      throw new AssertionError(
          "Nothing was generated at " + relativePath + "; generated: " + generatedSources.keySet());
    }

    return bytes;
  }

  public String generatedText(String relativePath) {
    return new String(generatedSource(relativePath), UTF_8);
  }

  public List<String> errors() {
    return textOf(Diagnostic.Kind.ERROR);
  }

  public List<String> warnings() {
    return textOf(Diagnostic.Kind.WARNING);
  }

  public List<String> notes() {
    return textOf(Diagnostic.Kind.NOTE);
  }

  private List<String> textOf(Diagnostic.Kind kind) {
    return diagnostics.stream()
        .filter(message -> message.kind() == kind)
        .map(CompilationOutcome.Message::text)
        .toList();
  }
}
