package io.github.vadimbabich.entitymetamodel.processor.emit;

record MetamodelUnit(String packageName, MetamodelClass root) {

  String qualifiedName() {
    return ImportScope.qualify(packageName, root.simpleName());
  }
}
