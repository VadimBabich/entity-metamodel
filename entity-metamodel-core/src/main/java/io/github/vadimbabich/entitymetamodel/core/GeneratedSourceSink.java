package io.github.vadimbabich.entitymetamodel.core;

/**
 * Frontend-neutral output seam: one generated compilation unit per call, in the model's entity
 * order, so a sink may stream.
 */
public interface GeneratedSourceSink {

  void accept(String fullyQualifiedClassName, String sourceText);
}
