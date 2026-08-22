package io.github.vadimbabich.entitymetamodel.processor;

import io.github.vadimbabich.entitymetamodel.core.GenerationDiagnostics;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

/** Adapts the reporting seam to the compiler, anchoring every message to the entity generated. */
final class MessagerDiagnostics implements GenerationDiagnostics {

  private final Messager messager;
  private final Element anchor;

  MessagerDiagnostics(Messager messager, Element anchor) {
    this.messager = messager;
    this.anchor = anchor;
  }

  @Override
  public void note(String message) {
    messager.printMessage(Diagnostic.Kind.NOTE, message, anchor);
  }

  @Override
  public void error(String message) {
    messager.printMessage(Diagnostic.Kind.ERROR, message, anchor);
  }
}
