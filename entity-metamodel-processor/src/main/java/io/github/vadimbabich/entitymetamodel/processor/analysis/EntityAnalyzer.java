package io.github.vadimbabich.entitymetamodel.processor.analysis;

import io.github.vadimbabich.entitymetamodel.core.EntityDescriptor;
import io.github.vadimbabich.entitymetamodel.core.SuperTypeContribution;
import io.github.vadimbabich.entitymetamodel.core.TypeKind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

/**
 * Reads entities out of the language model: which annotated types own a generated file, what each
 * one's attributes and supertype contributions are, and which entities are nested inside them.
 *
 * <p>One generated file per <em>root</em> entity is what lets the processor name a single
 * originating element, the condition for incremental compilation to key regeneration to one source.
 */
public final class EntityAnalyzer {

  // The runtime reserves this as its projected-label separator and rejects it in an alias — which a
  // generated metamodel would otherwise discover only when its static initializer ran.
  private static final String RESERVED_SEPARATOR = "__";

  private static final String OBJECT = "java.lang.Object";
  private static final String RECORD = "java.lang.Record";

  private final Elements elements;
  private final Types types;
  private final Messager messager;
  private final AttributeReader attributeReader;

  public EntityAnalyzer(ProcessingEnvironment processingEnvironment) {
    this.elements = processingEnvironment.getElementUtils();
    this.types = processingEnvironment.getTypeUtils();
    this.messager = processingEnvironment.getMessager();
    this.attributeReader = new AttributeReader(elements, types, messager);
  }

  /**
   * The entities that own a generated file: an annotated type whose immediately enclosing type is
   * not itself an entity. A nested entity of an entity is carried inside its owner instead.
   */
  public Set<TypeElement> emissionRootsAmong(Set<? extends Element> annotatedElements) {
    Set<TypeElement> roots = new LinkedHashSet<>();

    for (Element annotated : annotatedElements) {
      if (annotated instanceof TypeElement entity) {
        roots.add(emissionRootOf(entity));
      }
    }

    return roots;
  }

  /**
   * True when something the metamodel needs does not resolve <em>yet</em>: a type another processor
   * generates is unresolved in the round before it exists, so the caller defers, not reports.
   */
  public boolean waitsForUnresolvedTypes(TypeElement entity) {
    if (hasUnresolvedMemberInHierarchy(entity, (DeclaredType) entity.asType())) {
      return true;
    }

    // Nested entities share the generated file, so one of them waiting holds up the whole file.
    for (TypeElement nested : nestedEntitiesOf(entity)) {
      if (waitsForUnresolvedTypes(nested)) {
        return true;
      }
    }

    return false;
  }

  /** Empty when the entity cannot be read faithfully; the reason is reported before returning. */
  public Optional<EntityDescriptor> analyze(TypeElement entity) {
    if (entity.getSimpleName().toString().contains(RESERVED_SEPARATOR)) {
      messager.printMessage(
          Diagnostic.Kind.ERROR,
          "EM-E8: " + entity.getSimpleName() + " contains the reserved separator '"
              + RESERVED_SEPARATOR + "', which the runtime rejects when it aliases the entity;"
              + " rename the entity",
          entity);

      return Optional.empty();
    }
    if (!entity.getTypeParameters().isEmpty()) {
      // Its name cannot appear in a static field without raw-type warnings, which fails a consumer
      // building with -Werror on generated code.
      messager.printMessage(
          Diagnostic.Kind.NOTE,
          "EM-N7: " + entity.getSimpleName()
              + " is generic; generic entity types have no metamodel yet",
          entity);

      return Optional.empty();
    }
    DeclaredType entityType = (DeclaredType) entity.asType();

    // Silent by design: callers defer on waitsForUnresolvedTypes and the processor reports once the
    // rounds are over. This is the net for a nested entity reached recursively.
    if (hasUnresolvedMemberInHierarchy(entity, entityType)) {
      return Optional.empty();
    }

    // One map per entity: an accessor anywhere in the hierarchy annotates its property, wherever
    // the field was declared.
    Map<String, List<AnnotationMirror>> accessors = attributeReader.accessorAnnotationsOf(entity);

    EntityDescriptor.Builder descriptor = EntityDescriptor
        .builder(packageOf(entity), entity.getQualifiedName().toString(), kindOf(entity))
        .tableName(MappingAnnotations.declaredTableNameOf(entity))
        .attributes(attributeReader.attributesOf(entity, entityType, accessors));

    for (SuperTypeContribution contribution : superTypeContributionsOf(entityType, accessors)) {
      descriptor.superType(contribution);
    }
    for (TypeElement nestedEntity : nestedEntitiesOf(entity)) {
      analyze(nestedEntity).ifPresent(descriptor::nestedEntity);
    }

    return Optional.of(descriptor.build());
  }

  // Members come from the supertypes too, so an unresolved one anywhere abandons the entity:
  // reading past it emits a metamodel silently missing a real column.
  private boolean hasUnresolvedMemberInHierarchy(TypeElement entity, DeclaredType entityType) {
    if (attributeReader.hasUnresolvedMember(entity, entityType)) {
      return true;
    }

    Optional<DeclaredType> superClass = superClassOf(entityType);

    while (superClass.isPresent()) {
      DeclaredType contributing = superClass.get();

      // An unresolved supertype is an ERROR mirror with no members, so asking about its members
      // answers "none" and the entity emits without a single inherited column.
      if (DeclaredTypes.readabilityOf(contributing) == DeclaredTypes.Readability.UNRESOLVED) {
        return true;
      }

      TypeElement contributingElement = (TypeElement) contributing.asElement();

      if (attributeReader.hasUnresolvedMember(contributingElement, contributing)) {
        return true;
      }

      superClass = superClassOf(contributing);
    }

    return false;
  }

  private TypeElement emissionRootOf(TypeElement entity) {
    Element enclosing = entity.getEnclosingElement();

    if (enclosing instanceof TypeElement enclosingType
        && MappingAnnotations.isEntity(enclosingType)) {

      return emissionRootOf(enclosingType);
    }

    return entity;
  }

  private List<TypeElement> nestedEntitiesOf(TypeElement entity) {
    List<TypeElement> nested = new ArrayList<>();

    for (TypeElement candidate : ElementFilter.typesIn(entity.getEnclosedElements())) {
      if (MappingAnnotations.isEntity(candidate)) {
        nested.add(candidate);
      }
    }

    return nested;
  }

  // Nearest first, and nothing skipped for want of an annotation: the mapping context treats all
  // inherited state as part of the entity's table. The walk is over types rather than elements so
  // each supertype arrives instantiated — GenericBase<String>, not GenericBase<T>.
  private List<SuperTypeContribution> superTypeContributionsOf(
      DeclaredType entityType, Map<String, List<AnnotationMirror>> accessors) {
    List<SuperTypeContribution> contributions = new ArrayList<>();
    Optional<DeclaredType> superClass = superClassOf(entityType);

    while (superClass.isPresent()) {
      DeclaredType contributing = superClass.get();
      TypeElement contributingElement = (TypeElement) contributing.asElement();

      contributions.add(SuperTypeContribution.of(
          contributingElement.getQualifiedName().toString(),
          attributeReader.attributesOf(contributingElement, contributing, accessors)));

      superClass = superClassOf(contributing);
    }

    return contributions;
  }

  private Optional<DeclaredType> superClassOf(DeclaredType type) {
    for (TypeMirror candidate : types.directSupertypes(type)) {
      if (!(candidate instanceof DeclaredType superClass)) {
        continue;
      }

      TypeElement superType = (TypeElement) superClass.asElement();

      // Interfaces carry no state, and directSupertypes lists them after the superclass.
      if (superType.getKind() == ElementKind.INTERFACE) {
        continue;
      }

      String qualifiedName = superType.getQualifiedName().toString();

      if (qualifiedName.equals(OBJECT) || qualifiedName.equals(RECORD)) {
        return Optional.empty();
      }

      return Optional.of(superClass);
    }

    return Optional.empty();
  }

  private TypeKind kindOf(TypeElement entity) {
    if (entity.getKind() == ElementKind.RECORD) {
      return TypeKind.RECORD;
    }

    return TypeKind.CLASS;
  }

  private String packageOf(TypeElement entity) {
    return elements.getPackageOf(entity).getQualifiedName().toString();
  }
}
