package io.github.vadimbabich.entitymetamodel.processor;

/**
 * Whether a name is already taken by a type this processor did not generate. The distinction is the
 * point: a metamodel left by an earlier build is on the compile classpath and must be regenerated,
 * while a hand-written class of that name must not be overwritten.
 */
interface ForeignTypes {

  boolean declares(String qualifiedClassName);
}
