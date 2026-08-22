package io.github.vadimbabich.entitymetamodel.processor.testing;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Arrays;
import java.util.List;

/**
 * Byte-for-byte comparison of generated source against the golden corpus, with a readable diff when
 * it fails.
 *
 * <p>Bytes because that is what the contract freezes: a syntax-tree comparison passes while
 * whitespace, member order and encoding drift. The diff exists because no harness renders one.
 */
public final class MetamodelParity {

  private MetamodelParity() {
  }

  public static void assertIdentical(String label, byte[] expected, byte[] actual) {
    if (Arrays.equals(expected, actual)) {
      return;
    }

    throw new AssertionError(diffOf(label, expected, actual));
  }

  private static String diffOf(String label, byte[] expected, byte[] actual) {
    List<String> expectedLines = linesOf(expected);
    List<String> actualLines = linesOf(actual);

    StringBuilder message = new StringBuilder();

    message.append(label).append(" differs from the golden corpus (expected ")
        .append(expected.length).append(" bytes, got ").append(actual.length).append(")\n");

    for (int line = 0; line < Math.max(expectedLines.size(), actualLines.size()); line++) {
      String expectedLine = lineAt(expectedLines, line);
      String actualLine = lineAt(actualLines, line);

      if (!expectedLine.equals(actualLine)) {
        message.append("first difference at line ").append(line + 1).append('\n');
        message.append("  expected: ").append(expectedLine).append('\n');
        message.append("  actual:   ").append(actualLine).append('\n');

        return message.toString();
      }
    }

    message.append("lines are identical, so the difference is in line endings or trailing bytes\n");
    return message.toString();
  }

  private static List<String> linesOf(byte[] source) {
    return List.of(new String(source, UTF_8).split("\n", -1));
  }

  private static String lineAt(List<String> lines, int index) {
    if (index >= lines.size()) {
      return "<no line>";
    }

    return lines.get(index);
  }
}
