package io.github.vadimbabich.entitymetamodel.runtime;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API entry point that accepts raw SQL fragments, bypassing every safety the typed surface
 * provides. Callers own quoting, identifier validity and injection safety: never build a fragment
 * from untrusted input, and pass values as bind parameters.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RawSql {
}
