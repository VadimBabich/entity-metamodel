package com.example.diagnostics;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A house annotation over the house annotation — two levels, which Spring still resolves. */
@MyTransient
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface Computed {
}
