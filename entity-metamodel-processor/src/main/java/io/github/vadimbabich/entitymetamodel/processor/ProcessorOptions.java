package io.github.vadimbabich.entitymetamodel.processor;

import io.github.vadimbabich.entitymetamodel.processor.emit.InclusionPolicy;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;

/**
 * The processor's {@code -A} surface. The naming strategy is deliberately not an option yet: it has
 * to decide nesting and prefixes, not just name-to-name as in 1.x, and publishing an interface
 * before that shape settles would freeze the wrong one.
 */
final class ProcessorOptions {

  static final String REQUIRE_COLUMN_ANNOTATION = "entitymetamodel.requireColumnAnnotation";

  private final InclusionPolicy inclusionPolicy;

  private ProcessorOptions(InclusionPolicy inclusionPolicy) {
    this.inclusionPolicy = inclusionPolicy;
  }

  static Set<String> supportedNames() {
    return Set.of(REQUIRE_COLUMN_ANNOTATION);
  }

  static ProcessorOptions from(Map<String, String> options, Messager messager) {
    return new ProcessorOptions(inclusionPolicyFrom(options, messager));
  }

  InclusionPolicy inclusionPolicy() {
    return inclusionPolicy;
  }

  private static InclusionPolicy inclusionPolicyFrom(
      Map<String, String> options, Messager messager) {

    if (!options.containsKey(REQUIRE_COLUMN_ANNOTATION)) {
      return InclusionPolicy.SPRING_SEMANTICS;
    }

    // javac hands a bare -Akey over as a present key with a null value. Boolean.parseBoolean turned
    // that, and every typo, into a silent "off" — an opt-in that failed invisibly.
    String value = options.get(REQUIRE_COLUMN_ANNOTATION);

    if (value == null || value.isEmpty() || value.equalsIgnoreCase("true")) {
      return InclusionPolicy.REQUIRE_COLUMN_ANNOTATION;
    }
    if (value.equalsIgnoreCase("false")) {
      return InclusionPolicy.SPRING_SEMANTICS;
    }

    messager.printMessage(
        Diagnostic.Kind.ERROR,
        "EM-E9: -A" + REQUIRE_COLUMN_ANNOTATION + "=" + value
            + " is not readable as a boolean; pass true, false, or the bare flag");

    return InclusionPolicy.SPRING_SEMANTICS;
  }
}
