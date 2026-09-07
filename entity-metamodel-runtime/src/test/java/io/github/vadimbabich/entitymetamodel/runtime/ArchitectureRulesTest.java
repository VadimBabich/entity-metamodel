package io.github.vadimbabich.entitymetamodel.runtime;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The execution module's rule set, over the vocabulary module. The reactive rules pass here by
 * construction — this module has no reactive code — and stay so that they fire if it ever gains
 * any.
 *
 * <p>Signature means every channel a type reaches a consumer through: parameter, return and throws
 * types, field types, and supertypes, on anything public or protected.
 */
class ArchitectureRulesTest {

  private static final JavaClasses PRODUCTION_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("io.github.vadimbabich.entitymetamodel.runtime");

  // Revapi can only admit whole namespaces — one exposed type drags its entire signature closure
  // into that report — so the per-type policy lives here, where direct exposure is measurable.
  private static final Set<String> ADMITTED_FOREIGN_TYPES =
      Set.of("org.springframework.data.relational.core.mapping.RelationalMappingContext");

  private static final Set<String> OWN_PACKAGES =
      Set.of("io.github.vadimbabich.entitymetamodel.runtime");

  private static final Set<String> BLOCKING_CALLS =
      Set.of("block", "blockFirst", "blockLast", "toIterable", "toStream");

  private static final Set<String> SCHEDULING_CALLS = Set.of("subscribeOn", "publishOn");

  @Test
  void publicSignaturesCarryNoSubstrateRenderingType() {
    codeUnits().should(exposeNoTierSType()).check(PRODUCTION_CLASSES);
  }

  @Test
  void publicSignaturesExposeOnlyAdmittedForeignTypes() {
    codeUnits().should(exposeOnlyAdmittedTypes()).check(PRODUCTION_CLASSES);
  }

  @Test
  void publicFieldsExposeOnlyAdmittedForeignTypes() {
    fields().should(carryOnlyAdmittedTypes()).check(PRODUCTION_CLASSES);
  }

  @Test
  void publicClassesInheritOnlyAdmittedTypes() {
    classes().that().arePublic().should(inheritOnlyAdmittedTypes()).check(PRODUCTION_CLASSES);
  }

  @Test
  void nothingCollapsesAPublisherToABlockingShape() {
    noClasses()
        .should()
        .callMethodWhere(reactorCallNamed(BLOCKING_CALLS, "a blocking reactor call"))
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void nothingSchedules() {
    noClasses()
        .should()
        .callMethodWhere(reactorCallNamed(SCHEDULING_CALLS, "a scheduling operator"))
        .orShould()
        .dependOnClassesThat()
        .resideInAPackage("reactor.core.scheduler..")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void nothingSubscribesOrContinuesPastAnError() {
    noClasses()
        .should()
        .callMethodWhere(reactorCallNamed(Set.of("subscribe"), "a library-side subscribe"))
        .orShould()
        .callMethodWhere(
            reactorCallNamed(Set.of("onErrorContinue"), "an error-swallowing continuation"))
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void nothingIsTransactional() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("org.springframework.transaction.annotation.Transactional")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void staticStateIsImmutable() {
    fields().that().areStatic().should().beFinal().check(PRODUCTION_CLASSES);
  }

  private static boolean isTierS(JavaClass type) {
    String packageName = type.getPackageName();

    if (packageName.startsWith("org.springframework.data.relational.core.sql")) {
      return true;
    }
    if (packageName.startsWith("org.springframework.data.r2dbc.query")) {
      return true;
    }

    // startsWith, because the nested spec types count too. UpdateMapper, the other type the ADR
    // names, already sits inside the r2dbc.query glob.
    return type.getFullName().startsWith("org.springframework.data.r2dbc.core.StatementMapper");
  }

  private static boolean isAdmitted(JavaClass exposedType) {
    JavaClass elementType = exposedType.getBaseComponentType();

    if (elementType.isPrimitive() || elementType.getPackageName().startsWith("java.")) {
      return true;
    }
    if (OWN_PACKAGES.contains(elementType.getPackageName())) {
      return true;
    }
    return ADMITTED_FOREIGN_TYPES.contains(elementType.getFullName());
  }

  private static boolean isExported(JavaMember member) {
    Set<JavaModifier> modifiers = member.getModifiers();
    boolean memberIsVisible =
        modifiers.contains(JavaModifier.PUBLIC) || modifiers.contains(JavaModifier.PROTECTED);
    if (!memberIsVisible) {
      return false;
    }

    return isExported(member.getOwner());
  }

  private static boolean isExported(JavaClass owner) {
    if (owner.getModifiers().contains(JavaModifier.PUBLIC)) {
      return true;
    }

    for (JavaClass subclass : owner.getAllSubclasses()) {
      if (subclass.getModifiers().contains(JavaModifier.PUBLIC)) {
        return true;
      }
    }
    return false;
  }

  private static ArchCondition<JavaCodeUnit> exposeNoTierSType() {
    return new ArchCondition<>("expose no Tier-S type in their signature") {
      @Override
      public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
        if (!isExported(codeUnit)) {
          return;
        }

        for (JavaClass exposedType : involvedSignatureTypes(codeUnit)) {
          if (isTierS(exposedType.getBaseComponentType())) {
            events.add(
                SimpleConditionEvent.violated(
                    codeUnit,
                    codeUnit.getFullName() + " exposes " + exposedType.getName()));
          }
        }
      }
    };
  }

  private static ArchCondition<JavaCodeUnit> exposeOnlyAdmittedTypes() {
    return new ArchCondition<>("expose only admitted foreign types in their signature") {
      @Override
      public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
        if (!isExported(codeUnit)) {
          return;
        }

        for (JavaClass exposedType : involvedSignatureTypes(codeUnit)) {
          if (!isAdmitted(exposedType)) {
            events.add(
                SimpleConditionEvent.violated(
                    codeUnit,
                    codeUnit.getFullName() + " exposes unadmitted " + exposedType.getName()));
          }
        }
      }
    };
  }

  private static ArchCondition<JavaField> carryOnlyAdmittedTypes() {
    return new ArchCondition<>("carry only admitted foreign types") {
      @Override
      public void check(JavaField field, ConditionEvents events) {
        if (!isExported(field)) {
          return;
        }

        for (JavaClass exposedType : field.getType().getAllInvolvedRawTypes()) {
          if (!isAdmitted(exposedType)) {
            events.add(
                SimpleConditionEvent.violated(
                    field,
                    field.getFullName() + " exposes unadmitted " + exposedType.getName()));
          }
        }
      }
    };
  }

  private static ArchCondition<JavaClass> inheritOnlyAdmittedTypes() {
    return new ArchCondition<>("extend and implement only admitted types") {
      @Override
      public void check(JavaClass publicClass, ConditionEvents events) {
        Set<JavaClass> inheritedTypes = new LinkedHashSet<>();

        publicClass
            .getSuperclass()
            .ifPresent(superType -> inheritedTypes.addAll(superType.getAllInvolvedRawTypes()));
        for (JavaType implementedInterface : publicClass.getInterfaces()) {
          inheritedTypes.addAll(implementedInterface.getAllInvolvedRawTypes());
        }
        inheritedTypes.addAll(publicClass.getAllRawSuperclasses());
        inheritedTypes.addAll(publicClass.getAllRawInterfaces());

        for (JavaClass inheritedType : inheritedTypes) {
          if (!isAdmitted(inheritedType)) {
            events.add(
                SimpleConditionEvent.violated(
                    publicClass,
                    publicClass.getName() + " inherits unadmitted " + inheritedType.getName()));
          }
        }
      }
    };
  }

  private static Set<JavaClass> involvedSignatureTypes(JavaCodeUnit codeUnit) {
    Set<JavaClass> involvedTypes = new LinkedHashSet<>();

    for (JavaType parameterType : codeUnit.getParameterTypes()) {
      involvedTypes.addAll(parameterType.getAllInvolvedRawTypes());
    }
    involvedTypes.addAll(codeUnit.getReturnType().getAllInvolvedRawTypes());
    involvedTypes.addAll(codeUnit.getExceptionTypes());

    return involvedTypes;
  }

  private static DescribedPredicate<JavaMethodCall> reactorCallNamed(
      Set<String> methodNames, String description) {
    return new DescribedPredicate<>(description) {
      @Override
      public boolean test(JavaMethodCall call) {
        boolean reactorOwner = call.getTargetOwner().getPackageName().startsWith("reactor.");
        return reactorOwner && methodNames.contains(call.getName());
      }
    };
  }
}
