package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import java.util.Objects;

/** One value the statement expects, and the marker in its SQL that will carry it. */
public record Binding(String marker, Object value) {

  public Binding {
    Objects.requireNonNull(marker, "marker");
    Objects.requireNonNull(value, "value");
  }
}
