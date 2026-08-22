package io.github.vadimbabich.entitymetamodel.processor.testing;

import io.github.vadimbabich.entitymetamodel.core.GeneratedSourceSink;
import java.util.LinkedHashMap;
import java.util.Map;

/** Emitted sources, in emission order. */
public final class RecordingSink implements GeneratedSourceSink {

  private final Map<String, String> emitted = new LinkedHashMap<>();

  @Override
  public void accept(String fullyQualifiedClassName, String sourceText) {
    emitted.put(fullyQualifiedClassName, sourceText);
  }

  public Map<String, String> emitted() {
    return Map.copyOf(emitted);
  }

  public String sourceOf(String fullyQualifiedClassName) {
    String sourceText = emitted.get(fullyQualifiedClassName);

    if (sourceText == null) {
      throw new AssertionError(
          "Nothing was emitted for " + fullyQualifiedClassName + "; emitted: " + emitted.keySet());
    }

    return sourceText;
  }
}
