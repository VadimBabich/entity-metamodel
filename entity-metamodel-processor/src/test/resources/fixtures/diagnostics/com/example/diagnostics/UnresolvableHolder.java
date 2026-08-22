package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * An inner class can name its enclosing class's type parameter, so the member it inherits stays a
 * type variable no matter which type it is viewed from. Pathological for a mapped entity — Spring
 * could not instantiate it — but it compiles, and a processor has to answer for it. Two of them, so
 * the same supertype gap is read twice.
 */
public class UnresolvableHolder<T> {

  @Table("unresolvable_leaves")
  public class Leaf extends GenericBase<T> {

    @Id
    @Column("leaf_id")
    Long id;
  }

  @Table("unresolvable_twigs")
  public class Twig extends GenericBase<T> {

    @Id
    @Column("twig_id")
    Long id;
  }
}
