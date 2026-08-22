package com.example.diagnostics;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.data.relational.core.mapping.Table;

/** A house stereotype over @Table — a fully functional entity as far as the mapping context cares. */
@Table("composed_children")
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Aggregate {
}
