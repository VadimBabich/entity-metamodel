package io.github.vadimbabich.metadata.generator.r2dbc;

import static java.lang.String.format;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.google.common.graph.Graph;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import io.github.vadimbabich.metadata.api.EntityMetadataGenerator;
import io.github.vadimbabich.metadata.api.GeneratedClassNamingStrategy;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.lang.model.element.Modifier;
import org.apache.maven.plugin.logging.Log;

/**
 * Emits one metamodel class per root entity, mirroring the entity's own nesting: a nested entity
 * becomes a static nested class of its parent's metamodel.
 *
 * @author Vadim Babich
 */
// Consumes Guava's @Beta graph API; see GenerateEntityMetadataMojo.
@SuppressWarnings("UnstableApiUsage")
public class R2dbcEntityMetadataGenerator implements EntityMetadataGenerator {

  private final Log log;
  private final File outputDir;
  private final JavaClassGenerator r2dbcHolder;
  private final JavaClassGenerator extendedColumnClass;
  private final GeneratedClassNamingStrategy generatedClassNamingStrategy;

  public R2dbcEntityMetadataGenerator(GeneratedClassNamingStrategy generatedClassNamingStrategy,
      File outputDir,
      Log log) {
    this.log = log;
    this.outputDir = outputDir;
    this.generatedClassNamingStrategy = generatedClassNamingStrategy;

    this.r2dbcHolder = new R2DbcEntityTemplateStaticHolderGeneratorJava(outputDir, log);
    this.extendedColumnClass = new ColumnJavaClassGenerator(r2dbcHolder, outputDir, log);
  }


  @Override
  public void generateMetadataClasses(
      Graph<TypeDeclaration<?>> graph,
      Function<TypeDeclaration<?>, Set<String>> entityFieldsResolver
  ) throws IOException {

    Set<TypeDeclaration<?>> rootNodes = getRootNodes(graph);

    r2dbcHolder.generateSourceFile();
    extendedColumnClass.generateSourceFile();

    for(TypeDeclaration<?> rootTypeDeclaration : rootNodes){
      generateMetadataClass(rootTypeDeclaration,  graph, entityFieldsResolver);
    }
  }

  private void generateMetadataClass(
      TypeDeclaration<?> rootClassDeclaration,
      Graph<TypeDeclaration<?>> graph,
      Function<TypeDeclaration<?>, Set<String>> entityFieldsResolver
  ) throws IOException {

    entityFieldsResolver = safeResolver(entityFieldsResolver);
    String entityPackageName = getPackage(rootClassDeclaration);

    TypeSpec topLevelClass =
        buildMetadataClass(rootClassDeclaration, graph, entityFieldsResolver, false);

    JavaFile javaFile = JavaFile.builder(entityPackageName, topLevelClass)
        .addFileComment(JavaClassGenerator.FILE_HEADER)
        .build();

    javaFile.writeTo(outputDir);
    log.debug("Metadata class written to: " + outputDir);
  }

  private TypeSpec buildMetadataClass(
      TypeDeclaration<?> entityClassDeclaration,
      Graph<TypeDeclaration<?>> graph,
      Function<TypeDeclaration<?>, Set<String>> entityFieldsResolver,
      boolean isNested
  ) {
    String entityClassName = getEntityClassName(entityClassDeclaration);
    String targetClassName = generatedClassNamingStrategy.getMetadataClassName(entityClassName);

    log.debug(
        (isNested ? "Creating nested" : "Generating") + " metadata class: " + targetClassName);

    ClassName entityFullClassName = getEntityFullClassName(
        entityClassDeclaration,
        getPackage(entityClassDeclaration),
        entityClassName);

    TypeSpec.Builder classBuilder = TypeSpec.classBuilder(targetClassName)
        .addModifiers(Modifier.FINAL, Modifier.PUBLIC)
        .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
        .addMethod(createGetTableMethodSpec(entityFullClassName));

    if (isNested) {
      classBuilder.addModifiers(Modifier.STATIC);
    }

    addColumnFields(classBuilder, targetClassName, entityFullClassName,
        entityFieldsResolver.apply(entityClassDeclaration));

    for (TypeDeclaration<?> child : graph.successors(entityClassDeclaration)) {
      TypeSpec nested = buildMetadataClass(child, graph, entityFieldsResolver, true);
      classBuilder.addType(nested);
    }

    return classBuilder.build();
  }


  private MethodSpec createGetTableMethodSpec(ClassName entityFullClassName) {
    ClassName table = ClassName.get(JavaClassGenerator.SQL_PACKAGE, "Table");

    return MethodSpec.methodBuilder("getTable")
        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        .returns(table)
        .addStatement("return $T.getTable($T.class)", r2dbcHolder.className(), entityFullClassName)
        .build();
  }

  private String getEntityClassName(TypeDeclaration<?> entityClass) {
    return entityClass.getNameAsString();
  }

  private String getPackage(TypeDeclaration<?> entityClass) {
    @SuppressWarnings("unchecked")
    Optional<CompilationUnit> compilationUnit = entityClass.findAncestor(CompilationUnit.class);

    return compilationUnit
        .flatMap(CompilationUnit::getPackageDeclaration)
        .map(PackageDeclaration::getNameAsString)
        .orElseThrow(() -> new IllegalStateException("Package declaration not found"));
  }

  private void addColumnFields(TypeSpec.Builder classBuilder, String targetClassName,
      ClassName entityFullClassName, Collection<String> fields) {

    ClassName columnClassName = extendedColumnClass.className();

    fields.forEach(fieldName -> {
      String constantName = toConstantName(fieldName);
      log.debug(format("Generating field %s.%s as %s", targetClassName, constantName,
          columnClassName));

      classBuilder.addField(
          FieldSpec.builder(columnClassName, constantName, Modifier.PUBLIC, Modifier.STATIC,
                  Modifier.FINAL)
              .initializer("new $T($T.class, $S)", columnClassName, entityFullClassName,
                  fieldName)
              .build()
      );
    });
  }

  // Walks out through the enclosing types so a nested entity resolves to Outer.Inner rather than
  // to a top-level Inner, which would not compile.
  private ClassName getEntityFullClassName(TypeDeclaration<?> entityClass,
      String entityPackageName,
      String entityClassName
  ) {

    log.debug(format("Found entity class: %s", entityClassName));

    StringBuilder fullClassPath = new StringBuilder(entityPackageName);
    Optional<Node> parentNode = entityClass.getParentNode();

    while (
        parentNode.isPresent()
            &&
            parentNode.get() instanceof TypeDeclaration<?> clOrInterface
    ) {
      fullClassPath.append(".").append(clOrInterface.getNameAsString());
      parentNode = clOrInterface.getParentNode();
    }

    return ClassName.bestGuess(fullClassPath + "." + entityClassName);
  }

  private String toConstantName(String fieldName) {
    return fieldName
        // Also implemented by entity-metamodel-processor (ConstantNames); the two are byte-compared
        // against golden corpora that must agree, and no dependency links them. Change both.
        .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
        .replaceAll("([A-Z])([A-Z][a-z])", "$1_$2")
        .toUpperCase(Locale.ROOT);
  }

  private Set<TypeDeclaration<?>> getRootNodes(Graph<TypeDeclaration<?>> graph) {
    return graph.nodes().stream()
        .filter(n -> graph.predecessors(n).isEmpty())
        .collect(Collectors.toSet());
  }

  private <T, R> Function<T, Set<R>> safeResolver(Function<T, Set<R>> resolver) {
    return t -> Optional.ofNullable(resolver.apply(t)).orElse(Set.of());
  }
}
