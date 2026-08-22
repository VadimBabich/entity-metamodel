package io.github.vadimbabich.entitymetamodel.processor.emit;

import java.util.List;

/**
 * The generated-class naming rule, {@code X__} per nesting level. A nested entity of an entity is
 * decorated on its own name ({@code Vendor__.VendorPermissionView__}); one whose enclosers are not
 * entities has no owner metamodel and carries them as prefix instead
 * ({@code Wrapper.Attachment} to {@code Wrapper_Attachment__}).
 */
final class MetamodelNaming {

  private static final String SUFFIX = "__";

  private MetamodelNaming() {
  }

  static String topLevelName(List<String> simpleNameChain) {
    return String.join("_", simpleNameChain) + SUFFIX;
  }

  static String nestedName(String simpleName) {
    return simpleName + SUFFIX;
  }
}
