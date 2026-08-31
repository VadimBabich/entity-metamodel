package io.github.vadimbabich.entitymetamodel.bom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * The BOM's entries are maintained by hand and its description says the annotations artifact joins
 * when it lands, so it will at some point name a coordinate before the module producing it
 * exists — or stop naming one that does. Central is immutable, so a BOM that promises a
 * coordinate nothing builds is permanent, and a consumer importing it resolves nothing.
 */
class BomCoversTheReactorTest {

  private static final String REACTOR_ROOT_PROPERTY = "reactor.root";
  private static final Path REACTOR_ROOT = Path.of(System.getProperty(REACTOR_ROOT_PROPERTY, ".."));
  private static final Path REACTOR_ROOT_POM = REACTOR_ROOT.resolve("pom.xml");
  private static final Path BOM_POM = Path.of("pom.xml");
  private static final String BOM_ARTIFACT_ID = "entity-metamodel-bom";
  private static final String REACTOR_VERSION_REFERENCE = "${project.version}";

  @Test
  void theReactorRootIsReadable() {
    assertThat(REACTOR_ROOT_POM).as("reactor root POM, resolved to %s — without it every "
        + "assertion below examines nothing and passes. Surefire supplies the root as -D%s; "
        + "outside Maven this falls back to '..' relative to the working directory.",
        REACTOR_ROOT_POM.toAbsolutePath(), REACTOR_ROOT_PROPERTY).exists();

    assertThat(familyGroupId()).as("reactor groupId").isNotEmpty();
    assertThat(moduleDirectories()).as("reactor modules").isNotEmpty();
  }

  @Test
  void everyModuleDeclaredByTheReactorIsReadable() {
    for (String directory : moduleDirectories()) {
      Path modulePom = REACTOR_ROOT.resolve(directory).resolve("pom.xml");

      assertThat(modulePom).as("POM of declared module '%s'", directory).exists();
      assertThatCode(() -> documentRoot(modulePom))
          .as("POM of declared module '%s' parses", directory)
          .doesNotThrowAnyException();
    }
  }

  @Test
  void theBomManagesEveryArtifactTheReactorPublishes() {
    Set<String> published = publishedArtifactIds();
    Set<String> managed = managedFamilyEntries().keySet();

    assertThat(managed).as("artifacts this reactor publishes but the BOM does not manage — "
        + "consumers importing the BOM would resolve no version for them")
        .containsAll(published);
  }

  @Test
  void theBomManagesNothingTheReactorDoesNotBuild() {
    Set<String> published = publishedArtifactIds();
    Set<String> managed = managedFamilyEntries().keySet();

    assertThat(published).as("family artifacts the BOM manages but no module in this reactor "
        + "builds — the BOM would advertise a coordinate that does not exist")
        .containsAll(managed);
  }

  @Test
  void everyManagedEntryTracksTheReactorVersion() {
    Map<String, String> managed = managedFamilyEntries();

    assertThat(managed).as("a literal version here pins the family to a stale release the moment "
        + "the next one ships").allSatisfy(
            (artifactId, version) -> assertThat(version).isEqualTo(REACTOR_VERSION_REFERENCE));
  }

  private Set<String> publishedArtifactIds() {
    Set<String> published = new LinkedHashSet<>();

    for (String directory : moduleDirectories()) {
      // Maven lets a directory name and an artifactId diverge, so the coordinate has to come from
      // the module's own POM rather than from the module list.
      Element modulePom = documentRoot(REACTOR_ROOT.resolve(directory).resolve("pom.xml"));
      String artifactId = childText(modulePom, "artifactId");

      if (!BOM_ARTIFACT_ID.equals(artifactId)) {
        published.add(artifactId);
      }
    }

    return published;
  }

  /** Managed entries under the family groupId, by artifactId. Third-party pins promise nothing. */
  private Map<String, String> managedFamilyEntries() {
    String familyGroupId = familyGroupId();
    Element dependencyManagement = childElement(documentRoot(BOM_POM), "dependencyManagement");
    Element dependencies = childElement(dependencyManagement, "dependencies");

    Map<String, String> managed = new LinkedHashMap<>();

    for (Element dependency : childElements(dependencies, "dependency")) {
      if (familyGroupId.equals(childText(dependency, "groupId"))) {
        managed.put(childText(dependency, "artifactId"), childText(dependency, "version"));
      }
    }

    return managed;
  }

  private String familyGroupId() {
    return childText(documentRoot(REACTOR_ROOT_POM), "groupId");
  }

  private List<String> moduleDirectories() {
    Element modules = childElement(documentRoot(REACTOR_ROOT_POM), "modules");
    List<String> directories = new ArrayList<>();

    for (Element module : childElements(modules, "module")) {
      directories.add(module.getTextContent().trim());
    }

    return List.copyOf(directories);
  }

  private Element documentRoot(Path pom) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

      DocumentBuilder builder = factory.newDocumentBuilder();

      try (InputStream stream = Files.newInputStream(pom)) {
        return builder.parse(stream).getDocumentElement();
      }
    } catch (IOException | ParserConfigurationException | SAXException failure) {
      throw new IllegalStateException("cannot read " + pom, failure);
    }
  }

  /** Direct children only: a descendant search collects elements out of nested dependencies too. */
  private List<Element> childElements(Element parent, String name) {
    NodeList children = parent.getChildNodes();
    List<Element> matching = new ArrayList<>();

    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);

      if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getNodeName())) {
        matching.add((Element) child);
      }
    }

    return List.copyOf(matching);
  }

  private Element childElement(Element parent, String name) {
    List<Element> matching = childElements(parent, name);

    if (matching.isEmpty()) {
      throw new IllegalStateException(
          "<" + name + "> is missing from <" + parent.getNodeName() + ">");
    }

    return matching.get(0);
  }

  private String childText(Element parent, String name) {
    return childElement(parent, name).getTextContent().trim();
  }
}
