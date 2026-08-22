package io.github.vadimbabich.entitymetamodel.processor;

import io.github.vadimbabich.entitymetamodel.core.EntityDescriptor;
import io.github.vadimbabich.entitymetamodel.core.EntityModel;
import io.github.vadimbabich.entitymetamodel.core.MetamodelGenerator;
import io.github.vadimbabich.entitymetamodel.processor.analysis.EntityAnalyzer;
import io.github.vadimbabich.entitymetamodel.processor.analysis.MappingAnnotations;
import io.github.vadimbabich.entitymetamodel.processor.emit.MetamodelSourceGenerator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * Generates one metamodel per Spring Data Relational entity.
 *
 * <p>Two behaviours are easy to "tidy" into bugs. {@code process} returns {@code false} so other
 * processors still see {@code @Table}, which this one does not own; and generation happens while
 * rounds run, because Eclipse invokes processors over several last rounds and output produced only
 * in the final one is lost there.
 */
@SupportedAnnotationTypes(MappingAnnotations.TABLE)
public final class EntityMetamodelProcessor extends AbstractProcessor {

  private EntityAnalyzer analyzer;
  private MetamodelGenerator generator;

  // A round only presents elements from files written in the previous one, so an entity skipped
  // early is never reconsidered and its metamodel goes missing on a passing build.
  private final Set<String> deferred = new LinkedHashSet<>();

  @Override
  public synchronized void init(ProcessingEnvironment processingEnvironment) {
    super.init(processingEnvironment);

    this.analyzer = new EntityAnalyzer(processingEnvironment);
    this.generator = new MetamodelSourceGenerator(
        ProcessorOptions.from(
            processingEnvironment.getOptions(), processingEnvironment.getMessager())
            .inclusionPolicy(),
        this::typeExists);
  }

  @Override
  public Set<String> getSupportedOptions() {
    return ProcessorOptions.supportedNames();
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    // Elements and annotations only, so no language version is too new; pinning a release would
    // warn on every build with a newer -source.
    return SourceVersion.latestSupported();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
    if (round.processingOver()) {
      reportEntitiesStillWaiting();
      return false;
    }

    Set<TypeElement> candidates = new LinkedHashSet<>(takeResolvableDeferred());

    candidates.addAll(entitiesIn(annotations, round));

    for (TypeElement entity : candidates) {
      generateFor(entity);
    }

    return false;
  }

  // Removed from the set so generateFor can re-defer what is still waiting. A name that no longer
  // resolves stays put: its final-round note is the only thing that says the metamodel is missing.
  private List<TypeElement> takeResolvableDeferred() {
    List<TypeElement> retried = new ArrayList<>();

    for (String qualifiedName : List.copyOf(deferred)) {
      TypeElement entity = processingEnv.getElementUtils().getTypeElement(qualifiedName);

      if (entity != null) {
        deferred.remove(qualifiedName);
        retried.add(entity);
      }
    }

    return retried;
  }

  // javac reports the unresolvable type itself, so this only says which metamodel was lost to it.
  private void reportEntitiesStillWaiting() {
    for (String qualifiedName : deferred) {
      // The entity resolves even though its members do not, so the note anchors to it;
      // printMessage accepts a null element if even that has gone.
      processingEnv.getMessager().printMessage(
          Diagnostic.Kind.NOTE,
          "EM-N2: " + qualifiedName
              + " has a member type or supertype that does not resolve; no metamodel was"
              + " generated for it",
          processingEnv.getElementUtils().getTypeElement(qualifiedName));
    }
  }

  private boolean typeExists(String qualifiedClassName) {
    return processingEnv.getElementUtils().getTypeElement(qualifiedClassName) != null;
  }

  // A metamodel from an earlier build is not foreign: it is on the compile classpath of every
  // rebuild that skipped a clean, and refusing there fails the build for the wrong reason.
  private boolean isForeignType(String qualifiedClassName) {
    TypeElement existing = processingEnv.getElementUtils().getTypeElement(qualifiedClassName);

    if (existing == null) {
      return false;
    }

    for (AnnotationMirror annotation : existing.getAnnotationMirrors()) {
      if (annotation.getAnnotationType().toString()
          .equals(MetamodelSourceGenerator.GENERATED_ANNOTATION)) {
        return false;
      }
    }

    return true;
  }

  private Set<TypeElement> entitiesIn(
      Set<? extends TypeElement> annotations, RoundEnvironment round) {

    for (TypeElement annotation : annotations) {
      if (annotation.getQualifiedName().contentEquals(MappingAnnotations.TABLE)) {
        Set<? extends Element> annotated = round.getElementsAnnotatedWith(annotation);

        return analyzer.emissionRootsAmong(annotated);
      }
    }

    return Set.of();
  }

  private void generateFor(TypeElement entity) {
    if (analyzer.waitsForUnresolvedTypes(entity)) {
      deferred.add(entity.getQualifiedName().toString());
      return;
    }

    Optional<EntityDescriptor> analyzed = analyzer.analyze(entity);

    if (analyzed.isEmpty()) {
      return;
    }

    FilerSink sink = new FilerSink(
        processingEnv.getFiler(), this::isForeignType, processingEnv.getMessager(), entity);

    // One entity per call, not one model per round: a file derived from several entities could not
    // name a single originating element, which is what keeps consumers' builds incremental.
    generator.generate(
        EntityModel.of(List.of(analyzed.get())),
        sink,
        new MessagerDiagnostics(processingEnv.getMessager(), entity));
  }
}
