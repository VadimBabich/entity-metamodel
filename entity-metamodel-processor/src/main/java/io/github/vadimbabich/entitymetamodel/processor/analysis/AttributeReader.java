package io.github.vadimbabich.entitymetamodel.processor.analysis;

import io.github.vadimbabich.entitymetamodel.core.AnnotationFact;
import io.github.vadimbabich.entitymetamodel.core.AttributeDescriptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

/**
 * Reads the declared attributes of one type — record components or fields, with their exact
 * declared types and every annotation they carry.
 *
 * <p>Which attributes become generated members is the generator's decision, not this one's. What is
 * decided here is which fields are attributes at all, following the mapping context's own filter.
 */
final class AttributeReader {

  private final Elements elements;
  private final Types types;
  private final Messager messager;

  // A supertype's members are re-read per inheriting entity, so a gap in a shared base class would
  // otherwise be reported once per subclass.
  private final Set<String> reportedGaps = new HashSet<>();

  AttributeReader(Elements elements, Types types, Messager messager) {
    this.elements = elements;
    this.types = types;
    this.messager = messager;
  }

  /**
   * True when some member's type does not resolve. The compiler already reports the cause, so the
   * entity is abandoned rather than generated from a guess.
   */
  boolean hasUnresolvedMember(TypeElement type, DeclaredType containing) {
    // Annotations play no part in whether a type resolves, so no accessor map is needed.
    for (Member member : membersOf(type, containing, Map.of())) {
      DeclaredTypes.Readability readability = DeclaredTypes.readabilityOf(member.declaredType());

      if (readability == DeclaredTypes.Readability.UNRESOLVED) {
        return true;
      }
    }

    return false;
  }

  List<AttributeDescriptor> attributesOf(
      TypeElement type, DeclaredType containing, Map<String, List<AnnotationMirror>> accessors) {

    List<AttributeDescriptor> attributes = new ArrayList<>();

    for (Member member : membersOf(type, containing, accessors)) {
      DeclaredTypes.Readability readability = DeclaredTypes.readabilityOf(member.declaredType());

      if (readability != DeclaredTypes.Readability.READABLE) {
        reportOnce(type, member, "EM-N6: " + type.getSimpleName() + "." + member.name()
            + " has a type this metamodel cannot express and has no generated member");
      } else if (MappingAnnotations.referencesAnEntity(member.declaredType())) {
        // Not a column of this table, and @MappedCollection is optional — the type says so.
        reportOnce(type, member, "EM-N3: " + type.getSimpleName() + "." + member.name()
            + " refers to another aggregate; relationship members are not generated yet");
      } else {
        attributes.add(attributeOf(member));
      }
    }

    return attributes;
  }

  private AttributeDescriptor attributeOf(Member member) {
    List<AnnotationFact> annotations = AnnotationFacts.factsOf(member.annotations());

    return AttributeDescriptor.of(
        member.name(),
        DeclaredTypes.typeRefOf(member.declaredType()),
        carriesId(annotations),
        annotations);
  }

  // From the facts, not the raw mirrors: a composed annotation carrying @Id is in the facts, and
  // the flag has to agree with what the model shows.
  private boolean carriesId(List<AnnotationFact> annotations) {
    return AnnotationFact.presentIn(annotations, MappingAnnotations.ID);
  }

  private void reportOnce(TypeElement type, Member member, String message) {
    if (reportedGaps.add(type.getQualifiedName() + "#" + member.name())) {
      messager.printMessage(Diagnostic.Kind.NOTE, message, member.anchor());
    }
  }

  private List<Member> membersOf(
      TypeElement type, DeclaredType containing, Map<String, List<AnnotationMirror>> accessors) {

    if (type.getKind() == ElementKind.RECORD) {
      return recordComponentsOf(type);
    }

    return fieldsOf(type, containing, accessors);
  }

  /**
   * Accessor annotations for one entity, keyed by property name, across the whole hierarchy.
   * {@code getAllMembers} includes inherited methods and drops overridden ones, so a subclass
   * getter that exists only to annotate an inherited field is found — as the mapping context does.
   */
  Map<String, List<AnnotationMirror>> accessorAnnotationsOf(TypeElement entity) {
    Map<String, List<AnnotationMirror>> byPropertyName = new HashMap<>();

    for (ExecutableElement method : ElementFilter.methodsIn(elements.getAllMembers(entity))) {
      String propertyName = propertyNameOf(method.getSimpleName().toString());

      if (propertyName.isEmpty()) {
        continue;
      }

      byPropertyName
          .computeIfAbsent(propertyName, name -> new ArrayList<>())
          .addAll(method.getAnnotationMirrors());
    }

    return byPropertyName;
  }

  // Annotations come from the component and from the field it generates: Spring's target fields and
  // methods, not record components, so which element carries them depends on the compiler.
  private List<Member> recordComponentsOf(TypeElement type) {
    Map<String, VariableElement> backingFields = new HashMap<>();

    for (VariableElement field : ElementFilter.fieldsIn(type.getEnclosedElements())) {
      backingFields.put(field.getSimpleName().toString(), field);
    }

    List<Member> members = new ArrayList<>();

    for (RecordComponentElement component : type.getRecordComponents()) {
      String name = component.getSimpleName().toString();
      List<AnnotationMirror> annotations = new ArrayList<>(component.getAnnotationMirrors());
      VariableElement backingField = backingFields.get(name);

      if (backingField != null) {
        addMissing(annotations, backingField.getAnnotationMirrors());
      }

      members.add(new Member(name, component.asType(), annotations, component));
    }

    return members;
  }

  // Fields as the reading type sees them: `containing` substitutes T, so a field declared T in
  // GenericBase<T> is a String under GenericBase<String> instead of an inexpressible type variable.
  private List<Member> fieldsOf(
      TypeElement type, DeclaredType containing, Map<String, List<AnnotationMirror>> accessors) {

    List<Member> members = new ArrayList<>();

    for (VariableElement field : ElementFilter.fieldsIn(type.getEnclosedElements())) {
      if (!isProperty(field)) {
        continue;
      }

      String name = field.getSimpleName().toString();
      List<AnnotationMirror> annotations = new ArrayList<>(field.getAnnotationMirrors());

      addMissing(annotations, accessors.getOrDefault(name, List.of()));

      members.add(new Member(name, types.asMemberOf(containing, field), annotations, field));
    }

    return members;
  }

  private String propertyNameOf(String methodName) {
    for (String prefix : List.of("get", "set", "is")) {
      if (methodName.startsWith(prefix) && methodName.length() > prefix.length()) {
        return decapitalize(methodName.substring(prefix.length()));
      }
    }

    return "";
  }

  // The JavaBeans rule the mapping context introspects with: two leading capitals are left alone,
  // so getURL() names URL. Written out because java.beans lives in java.desktop, which a trimmed
  // image may not carry.
  private String decapitalize(String name) {
    if (name.length() > 1 && Character.isUpperCase(name.charAt(1))) {
      return name;
    }

    return Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }

  // The mapping context's own exclusions, and nothing wider: a field legitimately named price$usd
  // is a column, and dropping it silently is what this module does not do.
  private boolean isProperty(VariableElement field) {
    String name = field.getSimpleName().toString();

    return !field.getModifiers().contains(Modifier.STATIC)
        && !name.startsWith("this$")
        && !name.endsWith("$delegate");
  }

  private void addMissing(
      List<AnnotationMirror> annotations, List<? extends AnnotationMirror> candidates) {

    for (AnnotationMirror candidate : candidates) {
      if (!containsType(annotations, candidate)) {
        annotations.add(candidate);
      }
    }
  }

  private boolean containsType(List<AnnotationMirror> annotations, AnnotationMirror candidate) {
    String candidateType = candidate.getAnnotationType().toString();

    for (AnnotationMirror annotation : annotations) {
      if (annotation.getAnnotationType().toString().equals(candidateType)) {
        return true;
      }
    }

    return false;
  }

  private record Member(
      String name,
      TypeMirror declaredType,
      List<AnnotationMirror> annotations,
      Element anchor) {
  }
}
