package io.github.vadimbabich.entitymetamodel.core;

/**
 * Emits sources from the model through the sink, seeing no compiler, file system or build tool, so
 * the same generator runs under any frontend. Implementations must be stateless or effectively
 * immutable: a frontend may reuse one instance across rounds.
 */
public interface MetamodelGenerator {

  void generate(EntityModel model, GeneratedSourceSink sink, GenerationDiagnostics diagnostics);
}
