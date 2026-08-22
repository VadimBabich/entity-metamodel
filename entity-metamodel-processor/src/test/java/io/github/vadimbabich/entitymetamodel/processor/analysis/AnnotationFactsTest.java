package io.github.vadimbabich.entitymetamodel.processor.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.entitymetamodel.core.AnnotationFact;
import io.github.vadimbabich.entitymetamodel.processor.testing.FixtureCompiler;
import io.github.vadimbabich.entitymetamodel.processor.testing.Fixtures;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the model records for a member's annotations. The values are carried for consumers of the
 * model rather than for this contract, so they are not observable through generated output and are
 * asserted here against real mirrors.
 */
class AnnotationFactsTest {

  private static final String EMBEDDED =
      "org.springframework.data.relational.core.mapping.Embedded";

  @TempDir
  Path workDirectory;

  @Test
  void aDirectlyDeclaredAnnotationWinsOverTheSameOneReachedAsAMetaAnnotation() {
    FactsProbe probe = new FactsProbe();

    new FixtureCompiler(workDirectory).compile(
        Fixtures.sources(Fixtures.diagnosticsSource("EmbeddedConflict.java"),
            Fixtures.diagnosticsSource("Address.java")),
        probe);

    AnnotationFact embedded = probe.embeddedFact();

    // @Embedded.Nullable carries @Embedded(USE_NULL) and is declared first; the author's own
    // @Embedded(USE_EMPTY) is what the model must keep.
    assertThat(embedded.declaredValues().values().toString()).contains("USE_EMPTY");
    assertThat(probe.factNames()).contains(EMBEDDED + ".Nullable");
  }

  @SupportedAnnotationTypes(MappingAnnotations.TABLE)
  private static final class FactsProbe extends AbstractProcessor {

    private List<AnnotationFact> facts = List.of();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
      for (TypeElement annotation : annotations) {
        for (Element entity : round.getElementsAnnotatedWith(annotation)) {
          collectFactsOfAddressField((TypeElement) entity);
        }
      }

      return false;
    }

    private void collectFactsOfAddressField(TypeElement entity) {
      for (VariableElement field : ElementFilter.fieldsIn(entity.getEnclosedElements())) {
        if (field.getSimpleName().contentEquals("address")) {
          facts = AnnotationFacts.factsOf(field.getAnnotationMirrors());
        }
      }
    }

    AnnotationFact embeddedFact() {
      return facts.stream()
          .filter(fact -> fact.qualifiedName().equals(EMBEDDED))
          .findFirst()
          .orElseThrow(() -> new AssertionError("no @Embedded fact in " + factNames()));
    }

    List<String> factNames() {
      List<String> names = new ArrayList<>();

      facts.forEach(fact -> names.add(fact.qualifiedName()));
      return names;
    }
  }
}
