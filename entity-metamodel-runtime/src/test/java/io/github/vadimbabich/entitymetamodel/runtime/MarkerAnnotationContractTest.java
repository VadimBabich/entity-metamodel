package io.github.vadimbabich.entitymetamodel.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Pins the retention and targets the marker ADRs argue for.
 */
class MarkerAnnotationContractTest {

  @Test
  void generatedIsClassRetainedAndTypeTargeted() {
    Retention retention = Generated.class.getAnnotation(Retention.class);
    Target target = Generated.class.getAnnotation(Target.class);

    assertThat(retention.value()).isEqualTo(RetentionPolicy.CLASS);
    assertThat(target.value()).containsExactly(ElementType.TYPE);
  }

  @Test
  void generatedCarriesTheGeneratorIdentifier() throws Exception {
    Class<?> valueType = Generated.class.getDeclaredMethod("value").getReturnType();

    assertThat(valueType).isEqualTo(String[].class);
  }

  @Test
  void rawSqlIsRuntimeRetainedAndMarksMethodsAndTypes() {
    Retention retention = RawSql.class.getAnnotation(Retention.class);
    Target target = RawSql.class.getAnnotation(Target.class);

    assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
    assertThat(target.value()).containsExactlyInAnyOrder(ElementType.METHOD, ElementType.TYPE);
  }

  @Test
  void everyRawDoorCarriesTheMarker() throws NoSuchMethodException {
    Method rawFragment = SqlExpr.class.getMethod("raw", String.class, Object[].class);
    Method ascendingByExpression = ExpressionSort.class.getMethod("asc", SqlExpr.class);
    Method descendingByExpression = ExpressionSort.class.getMethod("desc", SqlExpr.class);

    assertThat(rawFragment.isAnnotationPresent(RawSql.class)).isTrue();
    assertThat(ascendingByExpression.isAnnotationPresent(RawSql.class)).isTrue();
    assertThat(descendingByExpression.isAnnotationPresent(RawSql.class)).isTrue();
  }
}
