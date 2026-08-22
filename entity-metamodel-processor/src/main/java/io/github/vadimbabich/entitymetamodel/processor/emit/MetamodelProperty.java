package io.github.vadimbabich.entitymetamodel.processor.emit;

import io.github.vadimbabich.entitymetamodel.core.TypeRef;

record MetamodelProperty(
    String constantName, String propertyName, TypeRef valueType, TypeRef rawType) {
}
