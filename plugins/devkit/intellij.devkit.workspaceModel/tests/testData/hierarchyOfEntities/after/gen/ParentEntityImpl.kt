package com.intellij.workspaceModel.test.api

import com.intellij.workspaceModel.storage.EntityInformation
import com.intellij.workspaceModel.storage.EntitySource
import com.intellij.workspaceModel.storage.EntityStorage
import com.intellij.workspaceModel.storage.GeneratedCodeApiVersion
import com.intellij.workspaceModel.storage.GeneratedCodeImplVersion
import com.intellij.workspaceModel.storage.ModifiableWorkspaceEntity
import com.intellij.workspaceModel.storage.MutableEntityStorage
import com.intellij.workspaceModel.storage.WorkspaceEntity
import com.intellij.workspaceModel.storage.impl.ConnectionId
import com.intellij.workspaceModel.storage.impl.ModifiableWorkspaceEntityBase
import com.intellij.workspaceModel.storage.impl.UsedClassesCollector
import com.intellij.workspaceModel.storage.impl.WorkspaceEntityBase
import com.intellij.workspaceModel.storage.impl.WorkspaceEntityData
import org.jetbrains.deft.annotations.Abstract
import org.jetbrains.deft.annotations.Open

@GeneratedCodeApiVersion(1)
@GeneratedCodeImplVersion(1)
open class ParentEntityImpl : ParentEntity, WorkspaceEntityBase() {

  companion object {


    val connections = listOf<ConnectionId>(
    )

  }

  @JvmField
  var _data1: String? = null
  override val data1: String
    get() = _data1!!

  @JvmField
  var _data2: String? = null
  override val data2: String
    get() = _data2!!

  override fun connectionIdList(): List<ConnectionId> {
    return connections
  }

  class Builder(val result: ParentEntityData?) : ModifiableWorkspaceEntityBase<ParentEntity>(), ParentEntity.Builder {
    constructor() : this(ParentEntityData())

    override fun applyToBuilder(builder: MutableEntityStorage) {
      if (this.diff != null) {
        if (existsInBuilder(builder)) {
          this.diff = builder
          return
        }
        else {
          error("Entity ParentEntity is already created in a different builder")
        }
      }

      this.diff = builder
      this.snapshot = builder
      addToBuilder()
      this.id = getEntityData().createEntityId()

      // Process linked entities that are connected without a builder
      processLinkedEntities(builder)
      checkInitialization() // TODO uncomment and check failed tests
    }

    fun checkInitialization() {
      val _diff = diff
      if (!getEntityData().isEntitySourceInitialized()) {
        error("Field WorkspaceEntity#entitySource should be initialized")
      }
      if (!getEntityData().isData1Initialized()) {
        error("Field GrandParentEntity#data1 should be initialized")
      }
      if (!getEntityData().isData2Initialized()) {
        error("Field ParentEntity#data2 should be initialized")
      }
    }

    override fun connectionIdList(): List<ConnectionId> {
      return connections
    }

    // Relabeling code, move information from dataSource to this builder
    override fun relabel(dataSource: WorkspaceEntity, parents: Set<WorkspaceEntity>?) {
      dataSource as ParentEntity
      this.entitySource = dataSource.entitySource
      this.data1 = dataSource.data1
      this.data2 = dataSource.data2
      if (parents != null) {
      }
    }


    override var entitySource: EntitySource
      get() = getEntityData().entitySource
      set(value) {
        checkModificationAllowed()
        getEntityData().entitySource = value
        changedProperty.add("entitySource")

      }

    override var data1: String
      get() = getEntityData().data1
      set(value) {
        checkModificationAllowed()
        getEntityData().data1 = value
        changedProperty.add("data1")
      }

    override var data2: String
      get() = getEntityData().data2
      set(value) {
        checkModificationAllowed()
        getEntityData().data2 = value
        changedProperty.add("data2")
      }

    override fun getEntityData(): ParentEntityData = result ?: super.getEntityData() as ParentEntityData
    override fun getEntityClass(): Class<ParentEntity> = ParentEntity::class.java
  }
}

class ParentEntityData : WorkspaceEntityData<ParentEntity>() {
  lateinit var data1: String
  lateinit var data2: String

  fun isData1Initialized(): Boolean = ::data1.isInitialized
  fun isData2Initialized(): Boolean = ::data2.isInitialized

  override fun wrapAsModifiable(diff: MutableEntityStorage): ModifiableWorkspaceEntity<ParentEntity> {
    val modifiable = ParentEntityImpl.Builder(null)
    modifiable.allowModifications {
      modifiable.diff = diff
      modifiable.snapshot = diff
      modifiable.id = createEntityId()
      modifiable.entitySource = this.entitySource
    }
    modifiable.changedProperty.clear()
    return modifiable
  }

  override fun createEntity(snapshot: EntityStorage): ParentEntity {
    val entity = ParentEntityImpl()
    entity._data1 = data1
    entity._data2 = data2
    entity.entitySource = entitySource
    entity.snapshot = snapshot
    entity.id = createEntityId()
    return entity
  }

  override fun getEntityInterface(): Class<out WorkspaceEntity> {
    return ParentEntity::class.java
  }

  override fun serialize(ser: EntityInformation.Serializer) {
  }

  override fun deserialize(de: EntityInformation.Deserializer) {
  }

  override fun createDetachedEntity(parents: List<WorkspaceEntity>): WorkspaceEntity {
    return ParentEntity(data1, data2, entitySource) {
    }
  }

  override fun getRequiredParents(): List<Class<out WorkspaceEntity>> {
    val res = mutableListOf<Class<out WorkspaceEntity>>()
    return res
  }

  override fun equals(other: Any?): Boolean {
    if (other == null) return false
    if (this::class != other::class) return false

    other as ParentEntityData

    if (this.entitySource != other.entitySource) return false
    if (this.data1 != other.data1) return false
    if (this.data2 != other.data2) return false
    return true
  }

  override fun equalsIgnoringEntitySource(other: Any?): Boolean {
    if (other == null) return false
    if (this::class != other::class) return false

    other as ParentEntityData

    if (this.data1 != other.data1) return false
    if (this.data2 != other.data2) return false
    return true
  }

  override fun hashCode(): Int {
    var result = entitySource.hashCode()
    result = 31 * result + data1.hashCode()
    result = 31 * result + data2.hashCode()
    return result
  }

  override fun hashCodeIgnoringEntitySource(): Int {
    var result = javaClass.hashCode()
    result = 31 * result + data1.hashCode()
    result = 31 * result + data2.hashCode()
    return result
  }

  override fun collectClassUsagesData(collector: UsedClassesCollector) {
    collector.sameForAllEntities = true
  }
}
