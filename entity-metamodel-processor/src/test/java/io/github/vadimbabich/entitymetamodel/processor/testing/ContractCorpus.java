package io.github.vadimbabich.entitymetamodel.processor.testing;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;

/** Access to the frozen contract corpus: the entity sources and the golden metamodels they pin. */
public final class ContractCorpus {

  public static final String PACKAGE = "com.example.contract";

  private static final String ROOT = "/contract-corpus";
  private static final String PACKAGE_PATH = PACKAGE.replace('.', '/');

  private ContractCorpus() {
  }

  public static String golden(String metamodelSimpleName) {
    return new String(goldenBytes(metamodelSimpleName), UTF_8);
  }

  public static byte[] goldenBytes(String metamodelSimpleName) {
    String resource = ROOT + "/expected/" + PACKAGE_PATH + "/" + metamodelSimpleName + ".java";

    try (InputStream bytes = ContractCorpus.class.getResourceAsStream(resource)) {
      if (bytes == null) {
        throw new IllegalArgumentException("No golden file on the classpath: " + resource);
      }

      return bytes.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read golden file " + resource, e);
    }
  }

  public static String generatedPathOf(String metamodelSimpleName) {
    return PACKAGE_PATH + "/" + metamodelSimpleName + ".java";
  }

  public static List<Path> sources() {
    Path sourceDirectory = sourceDirectory();

    return List.of(
        sourceDirectory.resolve("Account.java"),
        sourceDirectory.resolve("BaseDocument.java"),
        sourceDirectory.resolve("Inventory.java"),
        sourceDirectory.resolve("LegacyDocument.java"),
        sourceDirectory.resolve("Payment.java"),
        sourceDirectory.resolve("PaymentStatus.java"),
        sourceDirectory.resolve("Vendor.java"),
        sourceDirectory.resolve("Wrapper.java"));
  }

  private static Path sourceDirectory() {
    String resource = ROOT + "/sources/" + PACKAGE_PATH;
    URL located = ContractCorpus.class.getResource(resource);

    if (located == null) {
      throw new IllegalStateException("The corpus is not on the classpath: " + resource);
    }

    try {
      return Path.of(located.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException("Corpus directory " + resource + " is not a file", e);
    }
  }
}
