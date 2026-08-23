package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Carries an embedded value. Plain {@code @Embedded} rather than {@code @Embedded.Nullable}: the
 * latter meta-annotates a JSR-305 type that is absent from this classpath, and the resulting javac
 * warning fails the build under {@code -Werror}.
 */
@Table("people")
public class Person {

  @Id
  public Long id;

  public String name;

  @Embedded(onEmpty = Embedded.OnEmpty.USE_NULL)
  public Address address;
}
