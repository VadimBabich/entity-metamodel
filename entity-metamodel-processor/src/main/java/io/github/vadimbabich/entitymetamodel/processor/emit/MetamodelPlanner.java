package io.github.vadimbabich.entitymetamodel.processor.emit;

import io.github.vadimbabich.entitymetamodel.core.AttributeDescriptor;
import io.github.vadimbabich.entitymetamodel.core.EntityDescriptor;
import io.github.vadimbabich.entitymetamodel.core.GenerationDiagnostics;
import io.github.vadimbabich.entitymetamodel.core.SuperTypeContribution;
import io.github.vadimbabich.entitymetamodel.core.TypeRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one entity descriptor into the emission plan: inheritance flattened, excluded attributes
 * dropped, members put in the contract's order.
 *
 * <p>Every decision that could vary lives here, so the writer only renders: the model carries
 * inheritance as structure in declaration order, the generated shape is flat and name-sorted.
 */
final class MetamodelPlanner {

  // Querydsl's comparator: case-insensitive with a case-sensitive tiebreak, so the order is total
  // and compiler-independent.
  private static final Comparator<String> BY_MEMBER_NAME =
      String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

  private final AttributeInclusion inclusion;

  MetamodelPlanner(InclusionPolicy policy) {
    this.inclusion = new AttributeInclusion(policy);
  }

  MetamodelUnit plan(EntityDescriptor entity, GenerationDiagnostics diagnostics) {
    MetamodelClass root = new MetamodelClass(
        MetamodelNaming.topLevelName(entity.simpleNameChain()),
        String.join(".", entity.simpleNameChain()),
        propertiesOf(entity, diagnostics),
        nestedOf(entity, diagnostics));

    return new MetamodelUnit(entity.packageName(), root);
  }

  private List<MetamodelClass> nestedOf(
      EntityDescriptor entity, GenerationDiagnostics diagnostics) {

    List<MetamodelClass> nested = new ArrayList<>();

    for (EntityDescriptor nestedEntity : entity.nestedEntities()) {
      nested.add(new MetamodelClass(
          MetamodelNaming.nestedName(nestedEntity.simpleName()),
          String.join(".", nestedEntity.simpleNameChain()),
          propertiesOf(nestedEntity, diagnostics),
          nestedOf(nestedEntity, diagnostics)));
    }

    nested.sort(Comparator.comparing(MetamodelClass::simpleName, BY_MEMBER_NAME));
    return nested;
  }

  private List<MetamodelProperty> propertiesOf(
      EntityDescriptor entity, GenerationDiagnostics diagnostics) {

    List<MetamodelProperty> properties = new ArrayList<>();

    for (AttributeDescriptor attribute : flatten(entity, diagnostics)) {
      AttributeInclusion.Verdict verdict = inclusion.verdictOn(attribute);

      if (verdict == AttributeInclusion.Verdict.INCLUDED) {
        properties.add(propertyOf(attribute));
      } else {
        reportExclusion(entity, attribute, verdict, diagnostics);
      }
    }

    properties.sort(Comparator.comparing(MetamodelProperty::propertyName, BY_MEMBER_NAME));
    return withoutCollidingMemberNames(entity, properties, diagnostics);
  }

  // source_url and sourceUrl both become SOURCE_URL, and a property named entity collides with the
  // reserved handle. Either way javac reports against generated code with no hint at the cause, so
  // the collision is named here and the property dropped.
  private List<MetamodelProperty> withoutCollidingMemberNames(
      EntityDescriptor entity,
      List<MetamodelProperty> properties,
      GenerationDiagnostics diagnostics) {

    Map<String, MetamodelProperty> byConstantName = new LinkedHashMap<>();

    for (MetamodelProperty property : properties) {
      if (property.constantName().equals(MetamodelWriter.ENTITY_MEMBER)) {
        // No other property to rename, so name this one instead of implying a pair.
        diagnostics.error("EM-E7: " + entity.simpleName() + " property '" + property.propertyName()
            + "' maps to " + MetamodelWriter.ENTITY_MEMBER
            + ", the member name reserved for the entity handle; rename the property");
        continue;
      }

      MetamodelProperty existing = byConstantName.putIfAbsent(property.constantName(), property);

      if (existing != null) {
        diagnostics.error("EM-E7: " + entity.simpleName() + " properties '"
            + existing.propertyName() + "' and '" + property.propertyName()
            + "' both map to the member name " + property.constantName()
            + "; rename one of them");
      }
    }

    return List.copyOf(byConstantName.values());
  }

  // A name declared closer to the entity wins, mirroring the mapping context's subclass-first
  // resolution.
  private List<AttributeDescriptor> flatten(
      EntityDescriptor entity, GenerationDiagnostics diagnostics) {

    Map<String, AttributeDescriptor> byName = new LinkedHashMap<>();

    for (AttributeDescriptor attribute : entity.attributes()) {
      byName.put(attribute.name(), attribute);
    }

    for (SuperTypeContribution superType : entity.superTypes()) {
      for (AttributeDescriptor inherited : superType.attributes()) {
        AttributeDescriptor shadowing = byName.get(inherited.name());

        if (shadowing == null) {
          byName.put(inherited.name(), inherited);
        } else {
          reportShadowing(entity, superType, inherited, shadowing, diagnostics);
        }
      }
    }

    return List.copyOf(byName.values());
  }

  private MetamodelProperty propertyOf(AttributeDescriptor attribute) {
    return new MetamodelProperty(
        ConstantNames.upperSnake(attribute.name()),
        attribute.name(),
        PrimitiveTypes.boxed(attribute.declaredType()),
        rawTypeOf(attribute.declaredType()));
  }

  // The initializer passes a class literal, which cannot carry type arguments.
  private TypeRef rawTypeOf(TypeRef declaredType) {
    if (declaredType.typeArguments().isEmpty()) {
      return declaredType;
    }

    return new TypeRef(declaredType.qualifiedName(), List.of(), declaredType.arrayDimensions());
  }

  private void reportExclusion(
      EntityDescriptor entity,
      AttributeDescriptor attribute,
      AttributeInclusion.Verdict verdict,
      GenerationDiagnostics diagnostics) {

    // A transient property is excluded by design; the rest are gaps in what this version expresses,
    // and going quiet about them is how the 1.x pipeline lost members unnoticed.
    switch (verdict) {
      case EMBEDDED_VALUE -> diagnostics.note("EM-N3: " + entity.simpleName() + "."
          + attribute.name() + " is embedded; embedded values have no generated member yet");
      case MAPPED_COLLECTION_MEMBER -> diagnostics.note("EM-N3: " + entity.simpleName() + "."
          + attribute.name() + " is a mapped collection; relationship members are not generated"
          + " yet");
      case WITHOUT_COLUMN_ANNOTATION -> diagnostics.note("EM-N4: " + entity.simpleName() + "."
          + attribute.name() + " has no @Column and requireColumnAnnotation is on");
      case TRANSIENT_PROPERTY, INCLUDED -> {
      }
    }
  }

  private void reportShadowing(
      EntityDescriptor entity,
      SuperTypeContribution superType,
      AttributeDescriptor inherited,
      AttributeDescriptor shadowing,
      GenerationDiagnostics diagnostics) {

    if (inherited.declaredType().equals(shadowing.declaredType())) {
      return;
    }

    // Spring makes an incompatibly-typed override transient and recommends distinct names. The
    // winner may be the entity's own member or a nearer supertype's, so name the loser instead.
    diagnostics.note("EM-N5: " + superType.qualifiedName() + "." + inherited.name()
        + " is shadowed by a nearer declaration of '" + shadowing.name() + "' in "
        + entity.simpleName() + "'s hierarchy, with a different declared type;"
        + " the inherited property is not generated");
  }
}
