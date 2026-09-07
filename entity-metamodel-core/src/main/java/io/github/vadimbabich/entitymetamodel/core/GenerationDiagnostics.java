package io.github.vadimbabich.entitymetamodel.core;

/**
 * Frontend-neutral reporting seam. Messages carry no source anchor here: anchoring is the
 * frontend's territory — the annotation processor attaches the element it is generating for before
 * handing the message to the compiler.
 */
public interface GenerationDiagnostics {

  void note(String message);

  /**
   * No generator emits one yet. The level is carried because this interface is frozen and gated,
   * so adding one later would break every implementor.
   */
  void warning(String message);

  void error(String message);
}
