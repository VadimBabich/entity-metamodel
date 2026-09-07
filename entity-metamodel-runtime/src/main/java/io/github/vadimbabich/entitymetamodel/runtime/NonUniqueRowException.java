package io.github.vadimbabich.entitymetamodel.runtime;

import java.io.Serial;

/**
 * A description expected to match at most one row matched several. Subtypes
 * {@link IllegalStateException} so callers can catch the refusal without also catching the faults
 * that stay on the plain type.
 */
public final class NonUniqueRowException extends IllegalStateException {

  @Serial
  private static final long serialVersionUID = 1L;

  public NonUniqueRowException(String message) {
    super(message);
  }
}
