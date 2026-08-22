package io.github.vadimbabich.entitymetamodel.processor.testing;

import io.github.vadimbabich.entitymetamodel.core.GenerationDiagnostics;
import java.util.ArrayList;
import java.util.List;

public final class CollectingDiagnostics implements GenerationDiagnostics {

  private final List<String> notes = new ArrayList<>();
  private final List<String> errors = new ArrayList<>();

  @Override
  public void note(String message) {
    notes.add(message);
  }

  @Override
  public void error(String message) {
    errors.add(message);
  }

  public List<String> notes() {
    return List.copyOf(notes);
  }

  public List<String> errors() {
    return List.copyOf(errors);
  }
}
