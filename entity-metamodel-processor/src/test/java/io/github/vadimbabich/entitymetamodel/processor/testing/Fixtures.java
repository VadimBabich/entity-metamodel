package io.github.vadimbabich.entitymetamodel.processor.testing;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Test sources kept as resources, so the build never compiles them by accident. */
public final class Fixtures {

  private static final String ROOT = "/fixtures/";
  private static final String DIAGNOSTICS = "diagnostics";
  private static final String DIAGNOSTICS_PATH = "com/example/diagnostics";

  private Fixtures() {
  }

  public static String diagnosticsSource(String fixtureFileName) {
    return DIAGNOSTICS + "/" + DIAGNOSTICS_PATH + "/" + fixtureFileName;
  }

  public static String generatedPathOf(String metamodelSimpleName) {
    return DIAGNOSTICS_PATH + "/" + metamodelSimpleName + ".java";
  }

  public static List<Path> sources(String... relativePaths) {
    List<Path> sources = new ArrayList<>();

    for (String relativePath : relativePaths) {
      sources.add(source(relativePath));
    }

    return List.copyOf(sources);
  }

  public static Path source(String relativePath) {
    String resource = ROOT + relativePath;
    URL located = Fixtures.class.getResource(resource);

    if (located == null) {
      throw new IllegalArgumentException("No fixture on the classpath: " + resource);
    }

    try {
      return Path.of(located.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException("Fixture " + resource + " is not a file", e);
    }
  }
}
