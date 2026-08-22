package io.github.vadimbabich.entitymetamodel.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Generics and arrays carry verbatim — the difference between usable and decorative refs. */
class TypeRefFidelityTest {

  @Test
  void plainTypeRendersItsQualifiedName() {
    assertThat(TypeRef.of("java.lang.String").canonical()).isEqualTo("java.lang.String");
  }

  @Test
  void genericTypeCarriesItsArguments() {
    TypeRef listOfString = TypeRef.parameterized(
        "java.util.List", List.of(TypeRef.of("java.lang.String")));

    assertThat(listOfString.canonical()).isEqualTo("java.util.List<java.lang.String>");
  }

  @Test
  void deeperGenericsRenderVerbatim() {
    TypeRef mapStringInteger = TypeRef.parameterized(
        "java.util.Map",
        List.of(TypeRef.of("java.lang.String"), TypeRef.of("java.lang.Integer")));

    assertThat(mapStringInteger.canonical())
        .isEqualTo("java.util.Map<java.lang.String, java.lang.Integer>");
  }

  @Test
  void arrayDimensionsArePreserved() {
    assertThat(TypeRef.array(TypeRef.of("int"), 1).canonical()).isEqualTo("int[]");
    assertThat(TypeRef.array(TypeRef.of("java.lang.String"), 2).canonical())
        .isEqualTo("java.lang.String[][]");
  }

  @Test
  void dimensionsAccumulateWhenTheComponentIsItselfAnArray() {
    TypeRef intArray = TypeRef.array(TypeRef.of("int"), 1);

    assertThat(TypeRef.array(intArray, 1).canonical()).isEqualTo("int[][]");
    assertThat(TypeRef.array(TypeRef.array(intArray, 1), 1).canonical()).isEqualTo("int[][][]");
  }

  @Test
  void upperBoundedWildcardCarriesItsBoundVerbatim() {
    TypeRef listOfNumbers = TypeRef.parameterized(
        "java.util.List", List.of(WildcardArgument.upperBounded(TypeRef.of("java.lang.Number"))));

    assertThat(listOfNumbers.canonical()).isEqualTo("java.util.List<? extends java.lang.Number>");
  }

  @Test
  void lowerBoundedWildcardCarriesItsBoundVerbatim() {
    TypeRef consumerOfIntegers = TypeRef.parameterized(
        "java.util.function.Consumer",
        List.of(WildcardArgument.lowerBounded(TypeRef.of("java.lang.Integer"))));

    assertThat(consumerOfIntegers.canonical())
        .isEqualTo("java.util.function.Consumer<? super java.lang.Integer>");
  }

  @Test
  void unboundedWildcardRendersAsDeclaredRatherThanAsExtendsObject() {
    TypeRef anyClass = TypeRef.parameterized("java.lang.Class",
        List.of(WildcardArgument.unbounded()));

    assertThat(anyClass.canonical()).isEqualTo("java.lang.Class<?>");
  }

  @Test
  void anUnboundedWildcardCannotCarryABound() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> new WildcardArgument(
            WildcardArgument.Bound.UNBOUNDED, List.of(TypeRef.of("java.lang.Number"))));
  }

  @Test
  void aBoundedWildcardRequiresItsBound() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> new WildcardArgument(WildcardArgument.Bound.EXTENDS, List.of()));
  }

  @Test
  void typeRefsAreValueEqual() {
    TypeRef first = TypeRef.parameterized(
        "java.util.List", List.of(TypeRef.of("java.lang.String")));
    TypeRef second = TypeRef.parameterized(
        "java.util.List", List.of(TypeRef.of("java.lang.String")));

    assertThat(first).isEqualTo(second);
    assertThat(first.hashCode()).isEqualTo(second.hashCode());
  }
}
