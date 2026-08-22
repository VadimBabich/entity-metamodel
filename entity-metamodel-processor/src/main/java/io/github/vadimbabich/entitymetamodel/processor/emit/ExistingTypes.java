package io.github.vadimbabich.entitymetamodel.processor.emit;

/**
 * Whether a type of a given name exists in the compilation — the one question a generator cannot
 * answer alone. If the entity's package declares a class named {@code Integer}, writing
 * {@code Integer} there binds to it rather than to {@code java.lang.Integer}, silently.
 */
public interface ExistingTypes {

  /** Answers "nothing exists" — for generators run outside a compiler. */
  ExistingTypes NONE = qualifiedClassName -> false;

  boolean contains(String qualifiedClassName);
}
