// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.workspaceModel.codegen.classes

import com.intellij.workspaceModel.codegen.*
import com.intellij.workspaceModel.codegen.deft.meta.ObjClass
import com.intellij.workspaceModel.codegen.deft.meta.ObjProperty
import com.intellij.workspaceModel.codegen.deft.meta.ValueType
import com.intellij.workspaceModel.codegen.deft.meta.impl.KtInterfaceType
import com.intellij.workspaceModel.codegen.fields.implWsDataFieldCode
import com.intellij.workspaceModel.codegen.fields.implWsDataFieldInitializedCode
import com.intellij.workspaceModel.codegen.fields.javaType
import com.intellij.workspaceModel.codegen.utils.*
import com.intellij.workspaceModel.codegen.writer.allFields
import com.intellij.workspaceModel.codegen.writer.hasSetter
import com.intellij.workspaceModel.storage.*
import com.intellij.workspaceModel.storage.impl.SoftLinkable
import com.intellij.workspaceModel.storage.impl.UsedClassesCollector
import com.intellij.workspaceModel.storage.impl.WorkspaceEntityData
import com.intellij.workspaceModel.storage.impl.containers.toMutableWorkspaceList
import com.intellij.workspaceModel.storage.impl.containers.toMutableWorkspaceSet
import com.intellij.workspaceModel.storage.url.VirtualFileUrl

/**
 * - Soft links
 * - with PersistentId
 */

val ObjClass<*>.javaDataName
  get() = "${name.replace(".", "")}Data"

val ObjClass<*>.isEntityWithPersistentId: Boolean
  get() = superTypes.any { 
    it is KtInterfaceType && it.shortName == WorkspaceEntityWithPersistentId::class.java.simpleName
    || it is ObjClass<*> && (it.javaFullName.decoded == WorkspaceEntityWithPersistentId::class.java.name || it.isEntityWithPersistentId)
  }

fun ObjClass<*>.implWsDataClassCode(): String {
  val entityDataBaseClass = if (isEntityWithPersistentId) {
    "${WorkspaceEntityData::class.fqn}.WithCalculablePersistentId<$javaFullName>()"
  }
  else {
    "${WorkspaceEntityData::class.fqn}<$javaFullName>()"
  }
  val hasSoftLinks = hasSoftLinks()
  val softLinkable = if (hasSoftLinks) SoftLinkable::class.fqn else null
  return lines {
    section("class $javaDataName : ${sups(entityDataBaseClass, softLinkable?.encodedString)}") label@{
      listNl(allFields.noRefs().noEntitySource().noPersistentId()) { implWsDataFieldCode }

      listNl(allFields.noRefs().noEntitySource().noPersistentId().noOptional().noDefaultValue()) { implWsDataFieldInitializedCode }

      this@implWsDataClassCode.softLinksCode(this, hasSoftLinks)

      sectionNl(
        "override fun wrapAsModifiable(diff: ${MutableEntityStorage::class.fqn}): ${ModifiableWorkspaceEntity::class.fqn}<$javaFullName>") {
        line("val modifiable = $javaImplBuilderName(null)")
        line("modifiable.allowModifications {")
        line("  modifiable.diff = diff")
        line("  modifiable.snapshot = diff")
        line("  modifiable.id = createEntityId()")
        line("  modifiable.entitySource = this.entitySource")
        line("}")
        line("modifiable.changedProperty.clear()")
        line("return modifiable")
      }

      // --- createEntity
      sectionNl("override fun createEntity(snapshot: ${EntityStorage::class.fqn}): $javaFullName") {
        line("val entity = $javaImplName()")
        list(allFields.noRefs().noEntitySource().noPersistentId()) {
          if (hasSetter) {
            if (this.valueType is ValueType.Set<*> && !this.valueType.isRefType()) {
              "entity.$implFieldName = $name.toSet()"
            } else if (this.valueType is ValueType.List<*> && !this.valueType.isRefType()) {
              "entity.$implFieldName = $name.toList()"
            } else {
              "entity.$implFieldName = $name"
            }
          } else {
            "entity.$name = $name"
          }
        }
        line("entity.entitySource = entitySource")
        line("entity.snapshot = snapshot")
        line("entity.id = createEntityId()")
        line("return entity")
      }

      val collectionFields = allFields.noRefs().filter { it.valueType is ValueType.Collection<*, *> }
      if (collectionFields.isNotEmpty()) {
        sectionNl("override fun clone(): $javaDataName") {
          val fieldName = "clonedEntity"
          line("val $fieldName = super.clone()")
          line("$fieldName as $javaDataName")
          collectionFields.forEach { field ->
            if (field.valueType is ValueType.Set<*>) {
              line("$fieldName.${field.name} = $fieldName.${field.name}.${fqn7(Collection<*>::toMutableWorkspaceSet)}()")
            } else {
              line("$fieldName.${field.name} = $fieldName.${field.name}.${fqn7(Collection<*>::toMutableWorkspaceList)}()")
            }
          }
          line("return $fieldName")
        }
      }

      if (isEntityWithPersistentId) {
        val persistentIdField = fields.first { it.name == "persistentId" }
        val valueKind = persistentIdField.valueKind
        val methodBody = (valueKind as ObjProperty.ValueKind.Computable).expression
        if (methodBody.contains("return")) {
          if (methodBody.startsWith("{")) {
            line("override fun persistentId(): ${PersistentEntityId::class.fqn}<*> $methodBody \n")
          } else {
            sectionNl("override fun persistentId(): ${PersistentEntityId::class.fqn}<*>") {
                line(methodBody)
            }
          }
        } else {
          sectionNl("override fun persistentId(): ${PersistentEntityId::class.fqn}<*>") {
            if (methodBody.startsWith("=")) {
              line("return ${methodBody.substring(2)}")
            }
            else {
              line("return $methodBody")
            }
          }
        }
      }

      // --- getEntityInterface method
      sectionNl("override fun getEntityInterface(): Class<out ${WorkspaceEntity::class.fqn}>") {
        line("return $name::class.java")
      }

      sectionNl("override fun serialize(ser: ${EntityInformation.Serializer::class.fqn})") {
        //InterfaceTraverser(simpleTypes).traverse(this@implWsDataClassCode, SerializatorVisitor(this@sectionNl))
      }

      sectionNl("override fun deserialize(de: ${EntityInformation.Deserializer::class.fqn})") {
        //InterfaceTraverser(simpleTypes).traverse(this@implWsDataClassCode, DeserializationVisitor(this@sectionNl))
      }

      sectionNl("override fun createDetachedEntity(parents: List<${WorkspaceEntity::class.fqn}>): ${WorkspaceEntity::class.fqn}") {
        val noRefs = allFields.noRefs().noPersistentId()
        val mandatoryFields = allFields.mandatoryFields()
        val constructor = mandatoryFields.joinToString(", ") { it.name }.let { if (it.isNotBlank()) "($it)" else "" }
        val optionalFields = noRefs.filterNot { it in mandatoryFields }

        section("return $javaFullName$constructor") {
          optionalFields.forEach {
            line("this.${it.name} = this@$javaDataName.${it.name}")
          }
          allRefsFields.filterNot { it.valueType.getRefType().child }.forEach {
            val parentType = it.valueType
            if (parentType is ValueType.Optional) {
              line("this.${it.name} = parents.filterIsInstance<${parentType.type.javaType}>().singleOrNull()")
            } else {
              line("this.${it.name} = parents.filterIsInstance<${parentType.javaType}>().single()")
            }
          }
        }
      }

      sectionNl("override fun getRequiredParents(): List<Class<out WorkspaceEntity>>") {
        line("val res = mutableListOf<Class<out WorkspaceEntity>>()")
        allRefsFields.filterNot { it.valueType.getRefType().child }.forEach {
          val parentType = it.valueType
          if (parentType !is ValueType.Optional) {
            line("res.add(${parentType.javaType}::class.java)")
          }
        }
        line("return res")
      }

      // --- equals
      val keyFields = allFields.filter { it.isKey }
      sectionNl("override fun equals(other: Any?): Boolean") {
        line("if (other == null) return false")
        line("if (this::class != other::class) return false")

        lineWrapped("other as $javaDataName")

        list(allFields.noRefs().noPersistentId()) {
          "if (this.$name != other.$name) return false"
        }

        line("return true")
      }

      // --- equalsIgnoringEntitySource
      sectionNl("override fun equalsIgnoringEntitySource(other: Any?): Boolean") {
        line("if (other == null) return false")
        line("if (this::class != other::class) return false")

        lineWrapped("other as $javaDataName")

        list(allFields.noRefs().noEntitySource().noPersistentId()) {
          "if (this.$name != other.$name) return false"
        }

        line("return true")
      }

      // --- hashCode
      section("override fun hashCode(): Int") {
        line("var result = entitySource.hashCode()")
        list(allFields.noRefs().noEntitySource().noPersistentId()) {
          "result = 31 * result + $name.hashCode()"
        }
        line("return result")
      }

      // --- hashCodeIgnoringEntitySource
      section("override fun hashCodeIgnoringEntitySource(): Int") {
        line("var result = javaClass.hashCode()")
        list(allFields.noRefs().noEntitySource().noPersistentId()) {
          "result = 31 * result + $name.hashCode()"
        }
        line("return result")
      }

      if (keyFields.isNotEmpty()) {
        line()
        section("override fun equalsByKey(other: Any?): Boolean") {
          line("if (other == null) return false")
          line("if (this::class != other::class) return false")

          lineWrapped("other as $javaDataName")

          list(keyFields) {
            "if (this.$name != other.$name) return false"
          }

          line("return true")
        }
        line()
        section("override fun hashCodeByKey(): Int") {
          line("var result = javaClass.hashCode()")
          list(keyFields) {
            "result = 31 * result + $name.hashCode()"
          }
          line("return result")
        }
      }

      cacheCollector(this@lines)
    }
  }
}

fun List<ObjProperty<*, *>>.noRefs(): List<ObjProperty<*, *>> = this.filterNot { it.valueType.isRefType() }
fun List<ObjProperty<*, *>>.noEntitySource() = this.filter { it.name != "entitySource" }
fun List<ObjProperty<*, *>>.noPersistentId() = this.filter { it.name != "persistentId" }
fun List<ObjProperty<*, *>>.noOptional() = this.filter { it.valueType !is com.intellij.workspaceModel.codegen.deft.meta.ValueType.Optional<*> }
fun List<ObjProperty<*, *>>.noDefaultValue() = this.filter { it.valueKind == ObjProperty.ValueKind.Plain }

private fun ObjClass<*>.cacheCollector(linesBuilder: LinesBuilder) {
  val clazzes = HashSet<String>()
  val accessors = HashSet<String>()
  val objects = HashSet<String>()
  val res = allFields.map {
    it.valueType.getClasses(it.name, clazzes, accessors, objects)
  }.all{ it }
  linesBuilder.section("override fun collectClassUsagesData(collector: ${UsedClassesCollector::class.fqn})") {
    clazzes.forEach {
      line("collector.add(${it.toQualifiedName()}::class.java)")
    }
    objects.forEach {
      line("collector.addObject(${it.toQualifiedName()}::class.java)")
    }
    accessors.forEach {
      line(it)
    }
    line("collector.sameForAllEntities = $res")
  }
}

private fun ValueType<*>.getClasses(fieldName: String, clazzes: HashSet<String>, accessors: HashSet<String>, objects: HashSet<String>): Boolean {
  var res = true
  when (this) {
    is ValueType.List<*> -> {
      if (!this.isRefType()) {
        accessors.add("this.$fieldName?.let { collector.add(it::class.java) }")
        res = false
      }
      this.elementType.getClasses(fieldName, clazzes, accessors, objects)
      return res
    }
    is ValueType.Set<*> -> {
      if (!this.isRefType()) {
        accessors.add("this.$fieldName?.let { collector.add(it::class.java) }")
        res = false
      }
      this.elementType.getClasses(fieldName, clazzes, accessors, objects)
      return res
    }
    is ValueType.Blob -> {
      val className = this.javaClassName
      if (className !in setOf(VirtualFileUrl::class.java.name, EntitySource::class.java.name, PersistentEntityId::class.java.name)) {
        clazzes.add(className)
        return res
      }
      if (className == VirtualFileUrl::class.java.name) {
        accessors.add("this.$fieldName?.let { collector.add(it::class.java) }")
        return false
      }
      return true
    }
    is ValueType.DataClass -> {
      // Here we might filter PersistentIds and get them from the index, but in this case we would need to inspect their fields on the fly
      // Here we have all the information about the persistent ids and it's fields, so let's keep them here (at least for a while).
      clazzes.add(javaClassName)
      properties.forEach { property ->
        property.type.getClasses(fieldName, clazzes, accessors, objects)
      }
      return true
    }
    is ValueType.SealedClass -> {
      clazzes.add(javaClassName)
      this.subclasses.forEach { subclass ->
        subclass.getClasses(fieldName, clazzes, accessors, objects)
      }
      return true
    }
    is ValueType.Map<*, *> -> {
      if (!this.isRefType()) {
        accessors.add("this.$fieldName?.let { collector.add(it::class.java) }")
        res = false
      }
      this.keyType.getClasses(fieldName, clazzes, accessors, objects)
      this.valueType.getClasses(fieldName, clazzes, accessors, objects)
      return res
    }
    is ValueType.Optional -> {
      return this.type.getClasses(fieldName, clazzes, accessors, objects)
    }
    is ValueType.Structure -> {
      this.fields.forEach {
        it.getClasses(fieldName, clazzes, accessors, objects)
      }
      return true
    }
    is ValueType.Object<*> -> {
      objects.add(javaClassName)
      return true
    }
    else -> return true
  }
}
