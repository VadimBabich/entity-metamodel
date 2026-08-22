package com.example.diagnostics;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.data.annotation.Transient;

/** A house annotation over Spring's — one level of composition. */
@Transient
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.ANNOTATION_TYPE})
public @interface MyTransient {
}
