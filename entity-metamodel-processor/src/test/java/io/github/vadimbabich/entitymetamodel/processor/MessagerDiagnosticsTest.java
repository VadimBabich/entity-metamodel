package io.github.vadimbabich.entitymetamodel.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

import org.junit.jupiter.api.Test;

/**
 * The seam maps each {@link io.github.vadimbabich.entitymetamodel.core.GenerationDiagnostics}
 * level onto its compiler {@link Diagnostic.Kind}, anchored to the entity being generated.
 */
class MessagerDiagnosticsTest {

  private final RecordingMessager messager = new RecordingMessager();
  private final Element anchor = opaqueAnchor();
  private final MessagerDiagnostics diagnostics = new MessagerDiagnostics(messager, anchor);

  @Test
  void aNoteReachesTheCompilerAsANote() {
    diagnostics.note("resolved 3 entities");

    assertThat(messager.printed)
        .containsExactly(new PrintedMessage(Diagnostic.Kind.NOTE, "resolved 3 entities", anchor));
  }

  @Test
  void aWarningReachesTheCompilerAsAWarning() {
    diagnostics.warning("supertype outside the compilation");

    assertThat(messager.printed)
        .containsExactly(
            new PrintedMessage(
                Diagnostic.Kind.WARNING, "supertype outside the compilation", anchor));
  }

  @Test
  void anErrorReachesTheCompilerAsAnError() {
    diagnostics.error("unsupported member shape");

    assertThat(messager.printed)
        .containsExactly(
            new PrintedMessage(Diagnostic.Kind.ERROR, "unsupported member shape", anchor));
  }

  private record PrintedMessage(Diagnostic.Kind kind, String message, Element anchor) {
  }

  private static final class RecordingMessager implements Messager {

    private final List<PrintedMessage> printed = new ArrayList<>();

    @Override
    public void printMessage(Diagnostic.Kind kind, CharSequence message) {
      printMessage(kind, message, null);
    }

    @Override
    public void printMessage(Diagnostic.Kind kind, CharSequence message, Element element) {
      printed.add(new PrintedMessage(kind, message.toString(), element));
    }

    @Override
    public void printMessage(
        Diagnostic.Kind kind, CharSequence message, Element element, AnnotationMirror annotation) {
      printMessage(kind, message, element);
    }

    @Override
    public void printMessage(
        Diagnostic.Kind kind,
        CharSequence message,
        Element element,
        AnnotationMirror annotation,
        AnnotationValue value) {
      printMessage(kind, message, element);
    }
  }

  // The seam forwards the anchor untouched, and no real Element exists outside a compilation.
  private static Element opaqueAnchor() {
    return (Element)
        Proxy.newProxyInstance(
            Element.class.getClassLoader(),
            new Class<?>[]{Element.class},
            (proxy, method, arguments) -> {
              throw new UnsupportedOperationException(method.getName());
            });
  }
}
