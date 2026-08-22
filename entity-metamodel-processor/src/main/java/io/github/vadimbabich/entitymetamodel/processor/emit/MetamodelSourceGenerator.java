package io.github.vadimbabich.entitymetamodel.processor.emit;

import io.github.vadimbabich.entitymetamodel.core.EntityDescriptor;
import io.github.vadimbabich.entitymetamodel.core.EntityModel;
import io.github.vadimbabich.entitymetamodel.core.GeneratedSourceSink;
import io.github.vadimbabich.entitymetamodel.core.GenerationDiagnostics;
import io.github.vadimbabich.entitymetamodel.core.MetamodelGenerator;

/**
 * Emits the frozen contract shape: one compilation unit per entity in the model, with nested
 * entities carried inside their owner's metamodel.
 *
 * <p>Stateless and compiler-free: one instance serves every round, and a hand-built model exercises
 * it with no compilation at all.
 */
public final class MetamodelSourceGenerator implements MetamodelGenerator {

  /** The marker stamped on every generated metamodel — part of the frozen shape. */
  public static final String GENERATED_ANNOTATION =
      "io.github.vadimbabich.entitymetamodel.runtime.Generated";

  private final MetamodelPlanner planner;
  private final MetamodelWriter writer;

  public MetamodelSourceGenerator(InclusionPolicy policy, ExistingTypes existingTypes) {
    this.planner = new MetamodelPlanner(policy);
    this.writer = new MetamodelWriter(existingTypes);
  }

  @Override
  public void generate(
      EntityModel model, GeneratedSourceSink sink, GenerationDiagnostics diagnostics) {

    for (EntityDescriptor entity : model.entities()) {
      MetamodelUnit unit = planner.plan(entity, diagnostics);

      sink.accept(unit.qualifiedName(), writer.write(unit));
    }
  }
}
