package io.github.vadimbabich.entitymetamodel.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cross-module test code is duplicated because Maven's only way to share it is a test-jar, and
 * this family publishes what it builds. Duplication is tolerable; silent divergence is not: each
 * pair of copies nominates a marker, and everything from that marker down must stay byte-identical,
 * so a fix that reaches one copy and not the other fails here instead of leaving a module
 * enforcing a weaker rule.
 */
final class SourceRegionsStayInStep {

  private static final String MAVEN_REACTOR_ROOT_PROPERTY = "maven.reactor.root";
  private static final Path MAVEN_REACTOR_ROOT =
      Path.of(System.getProperty(MAVEN_REACTOR_ROOT_PROPERTY, ".."));

  private SourceRegionsStayInStep() {
  }

  static Path reactorFile(String reactorRelativePath) {
    return MAVEN_REACTOR_ROOT.resolve(reactorRelativePath);
  }

  static void assertReadable(Path comparedSource, String role) {
    assertThat(comparedSource).as("%s, resolved to %s — without it the comparison examines "
            + "nothing and passes. Surefire supplies the reactor root as -D%s.",
        role, comparedSource.toAbsolutePath(), MAVEN_REACTOR_ROOT_PROPERTY).exists();
  }

  static void assertIdenticalFrom(String marker, Path first, Path second, String remedy) {
    String firstRegion = regionOf(first, marker);
    String secondRegion = regionOf(second, marker);

    assertThat(firstRegion).as("the regions of %s and %s starting at '%s' have diverged. %s",
        first, second, marker, remedy).isEqualTo(secondRegion);
  }

  private static String regionOf(Path comparedSource, String marker) {
    String sourceText = readSource(comparedSource);
    int markerIndex = sourceText.indexOf(marker);

    assertThat(markerIndex).as("'%s' marks where the compared region begins in %s; without it "
        + "this test compares nothing", marker, comparedSource).isNotNegative();

    return sourceText.substring(markerIndex);
  }

  private static String readSource(Path comparedSource) {
    try {
      return Files.readString(comparedSource);
    } catch (IOException unreadable) {
      throw new UncheckedIOException("Cannot read " + comparedSource.toAbsolutePath(), unreadable);
    }
  }
}
