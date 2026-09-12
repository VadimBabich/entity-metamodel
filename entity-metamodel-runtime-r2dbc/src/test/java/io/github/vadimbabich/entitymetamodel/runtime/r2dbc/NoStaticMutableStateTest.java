package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The D3 invariant holds for the execution module too — the reactive rules bind every runtime
 * module, not just the resolution core. It matters more here: publishers are assembled on one
 * thread and subscribed on another, so a field written during assembly and read during rendering is
 * a data race rather than a style question.
 *
 * <p>Deliberately a copy of the resolution core's sweep rather than shared test infrastructure. The
 * reactive rules put this check in one ArchUnit suite spanning both modules; until that exists, a
 * module without its own copy is a module with no guard at all, and the executor lands here.
 */
class NoStaticMutableStateTest {

  private static final Class<?> MODULE_ANCHOR = QueryRenderer.class;

  // Pattern is documented immutable and safe for concurrent use.
  private static final List<Class<?>> IMMUTABLE_STATIC_TYPES =
      List.of(String.class, Class.class, Pattern.class);

  @Test
  void noClassInTheModuleDeclaresStaticMutableState() throws Exception {
    List<String> violations = new ArrayList<>();

    for (Class<?> moduleClass : allModuleClasses()) {
      for (Field field : moduleClass.getDeclaredFields()) {
        if (!Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
          continue;
        }

        if (!Modifier.isFinal(field.getModifiers())) {
          violations.add(moduleClass.getName() + "." + field.getName() + " is a non-final static");
          continue;
        }

        boolean immutable = field.getType().isPrimitive()
            || IMMUTABLE_STATIC_TYPES.contains(field.getType())
            || isImmutableEnum(field.getType());
        if (!immutable) {
          violations.add(moduleClass.getName() + "." + field.getName()
              + " is a final static of mutable-capable type " + field.getType().getName());
        }
      }
    }

    assertThat(violations).isEmpty();
  }

  /**
   * An enum is immutable when every instance field is final AND of an immutable type. Checking
   * only finality would certify a constant carrying a {@code final long[]} or a mutable list —
   * exactly the shared static this guard exists to forbid.
   */
  private static boolean isImmutableEnum(Class<?> type) {
    if (!type.isEnum()) {
      return false;
    }

    for (Field declared : type.getDeclaredFields()) {
      if (Modifier.isStatic(declared.getModifiers())) {
        continue;
      }

      if (!Modifier.isFinal(declared.getModifiers())) {
        return false;
      }

      boolean immutableFieldType = declared.getType().isPrimitive()
          || IMMUTABLE_STATIC_TYPES.contains(declared.getType());
      if (!immutableFieldType) {
        return false;
      }
    }

    return true;
  }

  private List<Class<?>> allModuleClasses() throws URISyntaxException {
    assertThat(MODULE_ANCHOR.getPackageName())
        .as("MODULE_ANCHOR must name a class in the module under test, or this sweep walks "
            + "another module's classes and passes without enforcing anything here")
        .startsWith(getClass().getPackageName());

    Path classesRoot = Path.of(
        MODULE_ANCHOR.getProtectionDomain().getCodeSource().getLocation().toURI());

    try (Stream<Path> classFiles = Files.walk(classesRoot)) {
      List<Class<?>> moduleClasses = new ArrayList<>();

      classFiles
          .filter(file -> file.toString().endsWith(".class"))
          .forEach(file -> moduleClasses.add(loadClass(classesRoot, file)));

      assertThat(moduleClasses).isNotEmpty();
      return moduleClasses;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot walk the module's classes", e);
    }
  }

  private Class<?> loadClass(Path classesRoot, Path classFile) {
    String binaryName = classesRoot.relativize(classFile).toString()
        .replace(java.io.File.separatorChar, '.')
        .replaceAll("\\.class$", "");

    try {
      return Class.forName(binaryName, false, MODULE_ANCHOR.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("Module class not loadable: " + binaryName, e);
    }
  }
}
