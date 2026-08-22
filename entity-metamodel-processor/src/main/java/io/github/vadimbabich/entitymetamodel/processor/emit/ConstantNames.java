package io.github.vadimbabich.entitymetamodel.processor.emit;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Property name to member name, per the canonical UPPER_SNAKE transformation of Jakarta
 * Persistence 3.2 §5.1.1.
 */
final class ConstantNames {

  // Two passes: lower-to-upper (capturedAt -> captured_At), then a capital run from the word after
  // it (sourceURLPath -> source_URL_Path). One pass gets acronyms wrong; the corpus freezes both.
  private static final Pattern LOWER_TO_UPPER = Pattern.compile("([a-z0-9])([A-Z])");
  private static final Pattern ACRONYM_BOUNDARY = Pattern.compile("([A-Z])([A-Z][a-z])");

  private ConstantNames() {
  }

  static String upperSnake(String propertyName) {
    String withWordBreaks = LOWER_TO_UPPER.matcher(propertyName).replaceAll("$1_$2");
    String withAcronymBreaks = ACRONYM_BOUNDARY.matcher(withWordBreaks).replaceAll("$1_$2");

    return withAcronymBreaks.toUpperCase(Locale.ROOT);
  }
}
