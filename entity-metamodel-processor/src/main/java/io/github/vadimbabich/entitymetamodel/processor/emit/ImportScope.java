package io.github.vadimbabich.entitymetamodel.processor.emit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides how each referenced type is written in one compilation unit, and which imports that
 * implies.
 *
 * <p>Two rules carry the weight: a simple name is used only for a type in the unit's own package,
 * never a shared prefix, since Java has no relative package resolution; and at most one type claims
 * a simple name, the own package winning it. Losing the second is worse than a compile error — a
 * consumer type named {@code Integer} would quietly capture {@code java.lang.Integer}.
 */
final class ImportScope {

  private static final String JAVA_LANG = "java.lang.";

  private final Map<String, String> renderings;
  private final List<String> imports;

  private ImportScope(Map<String, String> renderings, List<String> imports) {
    this.renderings = renderings;
    this.imports = imports;
  }

  static ImportScope of(
      String packageName,
      Collection<String> referencedTypes,
      Collection<String> declaredNames,
      ExistingTypes existingTypes) {

    Set<String> claimed = new HashSet<>(declaredNames);
    Map<String, String> renderings = new HashMap<>();
    Set<String> importable = new LinkedHashSet<>();

    // Forced renderings first, so the claims are complete before anything is imported.
    for (String referenced : new TreeSet<>(referencedTypes)) {
      if (hasNoPackage(referenced)) {
        renderings.put(referenced, referenced);
      } else if (isInPackage(packageName, referenced)) {
        String simpleName = simpleNameOf(referenced);

        // Own declarations were claimed first: a nested metamodel named Inner__ would otherwise
        // capture a same-package property type of that name, silently and compilably.
        if (claimed.contains(simpleName)) {
          renderings.put(referenced, referenced);
        } else {
          renderings.put(referenced, simpleName);
          claimed.add(simpleName);
        }
      } else {
        importable.add(referenced);
      }
    }

    List<String> imports = new ArrayList<>();

    for (String candidate : importable) {
      String simpleName = simpleNameOf(candidate);

      if (claimed.contains(simpleName) || claimsMoreThanOne(importable, simpleName)) {
        renderings.put(candidate, candidate);
        continue;
      }

      if (isDirectlyInJavaLang(candidate)) {
        // A java.lang type is written bare and cannot be imported over a same-package class of that
        // name, so ask whether one exists before shortening it.
        if (existingTypes.contains(qualify(packageName, simpleName))) {
          renderings.put(candidate, candidate);
        } else {
          renderings.put(candidate, simpleName);
        }
        continue;
      }

      // Canonical name, valid for a nested type too; a single-type import shadows a same-package
      // class of that name.
      renderings.put(candidate, simpleName);
      imports.add(candidate);
    }

    imports.sort(Comparator.naturalOrder());
    return new ImportScope(Map.copyOf(renderings), List.copyOf(imports));
  }

  List<String> imports() {
    return imports;
  }

  String render(String qualifiedTypeName) {
    String rendering = renderings.get(qualifiedTypeName);

    if (rendering == null) {
      throw new IllegalStateException(
          "Type " + qualifiedTypeName + " was rendered without being collected first");
    }

    return rendering;
  }

  private static boolean claimsMoreThanOne(Collection<String> importable, String simpleName) {
    int claimants = 0;

    for (String candidate : importable) {
      if (simpleNameOf(candidate).equals(simpleName)) {
        claimants++;
      }
    }

    return claimants > 1;
  }

  static String qualify(String packageName, String simpleName) {
    if (packageName.isEmpty()) {
      return simpleName;
    }

    return packageName + "." + simpleName;
  }

  private static boolean isInPackage(String packageName, String qualifiedTypeName) {
    if (packageName.isEmpty()) {
      return !qualifiedTypeName.contains(".");
    }
    if (!qualifiedTypeName.startsWith(packageName + ".")) {
      return false;
    }

    return !qualifiedTypeName.substring(packageName.length() + 1).contains(".");
  }

  private static boolean isDirectlyInJavaLang(String qualifiedTypeName) {
    return qualifiedTypeName.startsWith(JAVA_LANG)
        && !qualifiedTypeName.substring(JAVA_LANG.length()).contains(".");
  }

  // A primitive, or a type in the default package: written as-is either way.
  private static boolean hasNoPackage(String qualifiedTypeName) {
    return !qualifiedTypeName.contains(".");
  }

  private static String simpleNameOf(String qualifiedTypeName) {
    return qualifiedTypeName.substring(qualifiedTypeName.lastIndexOf('.') + 1);
  }
}
