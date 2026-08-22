package io.github.vadimbabich.entitymetamodel.processor.emit;

import java.util.List;

/**
 * One metamodel class in emission order — entity handle, refs, nested metamodels — so the writer
 * has no ordering decisions left. {@code entityTypeName} is the entity as this unit writes it
 * ({@code Vendor.VendorPermissionView}), taken from the descriptor rather than re-derived from a
 * qualified name, which cannot say where a package ends.
 */
record MetamodelClass(
    String simpleName,
    String entityTypeName,
    List<MetamodelProperty> properties,
    List<MetamodelClass> nested) {

  MetamodelClass {
    properties = List.copyOf(properties);
    nested = List.copyOf(nested);
  }

  /** The outermost name this unit writes for the entity — an import may not shadow it. */
  String entityRootName() {
    int firstDot = entityTypeName.indexOf('.');

    if (firstDot < 0) {
      return entityTypeName;
    }

    return entityTypeName.substring(0, firstDot);
  }
}
