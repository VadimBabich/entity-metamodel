package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

/** Enum-typed property: the driver cannot encode one, so it must reach the bind path converted. */
public enum AccountState {
  ACTIVE,
  CLOSED,
}
