// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.workspaceModel.storage.impl

import com.esotericsoftware.kryo.kryo5.Kryo
import com.esotericsoftware.kryo.kryo5.KryoException
import com.esotericsoftware.kryo.kryo5.Registration
import com.esotericsoftware.kryo.kryo5.Serializer
import com.esotericsoftware.kryo.kryo5.io.Input
import com.esotericsoftware.kryo.kryo5.io.Output
import com.esotericsoftware.kryo.kryo5.objenesis.instantiator.ObjectInstantiator
import com.esotericsoftware.kryo.kryo5.objenesis.strategy.StdInstantiatorStrategy
import com.esotericsoftware.kryo.kryo5.serializers.CollectionSerializer
import com.esotericsoftware.kryo.kryo5.serializers.DefaultSerializers
import com.esotericsoftware.kryo.kryo5.serializers.FieldSerializer
import com.esotericsoftware.kryo.kryo5.serializers.MapSerializer
import com.google.common.collect.HashBiMap
import com.google.common.collect.HashMultimap
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.registry.Registry
import com.intellij.util.ReflectionUtil
import com.intellij.util.SmartList
import com.intellij.util.containers.*
import com.intellij.workspaceModel.storage.*
import com.intellij.workspaceModel.storage.impl.containers.*
import com.intellij.workspaceModel.storage.impl.containers.BidirectionalMap
import com.intellij.workspaceModel.storage.impl.indices.*
import com.intellij.workspaceModel.storage.url.VirtualFileUrl
import com.intellij.workspaceModel.storage.url.VirtualFileUrlManager
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenCustomHashMap
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import org.jetbrains.annotations.TestOnly
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.util.*
import kotlin.reflect.KClass
import kotlin.system.measureNanoTime

private val LOG = logger<EntityStorageSerializerImpl>()

class DefaultListSerializer<T : List<*>> : CollectionSerializer<T>() {
  override fun create(kryo: Kryo, input: Input, type: Class<out T>, size: Int): T {
    val registration: Registration = kryo.getRegistration(type)
    if (List::class.java.isAssignableFrom(type) && registration.instantiator == null) {
      return ArrayList<Any>(size) as T
    }
    return super.create(kryo, input, type, size)
  }
}

class DefaultSetSerializer<T : Set<*>> : CollectionSerializer<T>() {
  override fun create(kryo: Kryo, input: Input, type: Class<out T>, size: Int): T {
    val registration: Registration = kryo.getRegistration(type)
    if (Set::class.java.isAssignableFrom(type) && registration.instantiator == null) {
      return HashSet<Any>(size) as T
    }
    return super.create(kryo, input, type, size)
  }
}

class DefaultMapSerializer<T : Map<*, *>> : MapSerializer<T>() {
  override fun create(kryo: Kryo, input: Input, type: Class<out T>, size: Int): T {
    val registration: Registration = kryo.getRegistration(type)
    if (Map::class.java.isAssignableFrom(type) && registration.instantiator == null) {
      return HashMap<Any, Any>(size) as T
    }
    return super.create(kryo, input, type, size)
  }
}

class EntityStorageSerializerImpl(
  private val typesResolver: EntityTypesResolver,
  private val virtualFileManager: VirtualFileUrlManager,
  private val versionsContributor: () -> Map<String, String> = { emptyMap() },
) : EntityStorageSerializer {
  companion object {
    const val SERIALIZER_VERSION = "v40"
  }

  internal val KRYO_BUFFER_SIZE = 64 * 1024

  private val interner = HashSetInterner<SerializableEntityId>()
  private val typeInfoInterner = HashSetInterner<TypeInfo>()

  @set:TestOnly
  override var serializerDataFormatVersion: String = SERIALIZER_VERSION

  internal fun createKryo(): Pair<Kryo, MutableMap<TypeInfo, Int>> {
    val kryo = Kryo()

    val classesCache: MutableMap<TypeInfo, Int> = HashMap()

    kryo.setAutoReset(false)
    kryo.references = true
    kryo.instantiatorStrategy = StdInstantiatorStrategy()

    kryo.addDefaultSerializer(VirtualFileUrl::class.java, VirtualFileUrlSerializer())
    kryo.addDefaultSerializer(List::class.java, DefaultListSerializer::class.java)
    kryo.addDefaultSerializer(Set::class.java, DefaultSetSerializer::class.java)
    kryo.addDefaultSerializer(Map::class.java, DefaultMapSerializer::class.java)

    kryo.register(EntityId::class.java, EntityIdSerializer(classesCache))
    kryo.register(HashMultimap::class.java, HashMultimapSerializer())
    kryo.register(ConnectionId::class.java, ConnectionIdSerializer(classesCache))
    kryo.register(ImmutableEntitiesBarrel::class.java, ImmutableEntitiesBarrelSerializer(classesCache))
    kryo.register(ChildEntityId::class.java, ChildEntityIdSerializer(classesCache))
    kryo.register(ParentEntityId::class.java, ParentEntityIdSerializer(classesCache))
    kryo.register(ObjectOpenHashSet::class.java, ObjectOpenHashSetSerializer())
    kryo.register(PersistentIdInternalIndex::class.java, PersistentIdIndexSerializer(classesCache))
    kryo.register(EntityStorageInternalIndex::class.java, EntityStorageIndexSerializer(classesCache))
    kryo.register(MultimapStorageIndex::class.java, MultimapStorageIndexSerializer(classesCache))
    kryo.register(BidirectionalLongMultiMap::class.java, EntityId2JarDirSerializer(classesCache))
    kryo.register(Object2ObjectOpenCustomHashMap::class.java, Vfu2EntityIdSerializer(classesCache))
    kryo.register(Long2ObjectOpenHashMap::class.java, EntityId2VfuSerializer(classesCache))

    kryo.register(TypeInfo::class.java)

    // TODO Dedup with OCSerializers
    // TODO Reuse OCSerializer.registerUtilitySerializers ?
    // TODO Scan OCSerializer for useful kryo settings and tricks
    kryo.register(List::class.java)
    kryo.register(ArrayList::class.java)
    kryo.register(HashMap::class.java)
    kryo.register(SmartList::class.java).instantiator = ObjectInstantiator { SmartList<Any>() }
    kryo.register(LinkedHashMap::class.java).instantiator = ObjectInstantiator { LinkedHashMap<Any, Any>() }
    kryo.register(BidirectionalMap::class.java).instantiator = ObjectInstantiator { BidirectionalMap<Any, Any>() }
    kryo.register(BidirectionalSetMap::class.java).instantiator = ObjectInstantiator { BidirectionalSetMap<Any, Any>() }
    kryo.register(BidirectionalMultiMap::class.java).instantiator = ObjectInstantiator { BidirectionalMultiMap<Any, Any>() }
    kryo.register(HashBiMap::class.java).instantiator = ObjectInstantiator { HashBiMap.create<Any, Any>() }
    kryo.register(LinkedHashSet::class.java).instantiator = ObjectInstantiator { LinkedHashSet<Any>() }
    kryo.register(LinkedBidirectionalMap::class.java).instantiator = ObjectInstantiator { LinkedBidirectionalMap<Any, Any>() }
    kryo.register(Int2IntOpenHashMap::class.java).instantiator = ObjectInstantiator { Int2IntOpenHashMap() }
    kryo.register(ObjectOpenHashSet::class.java).instantiator = ObjectInstantiator { ObjectOpenHashSet<Any>() }
    kryo.register(Object2ObjectOpenHashMap::class.java).instantiator = ObjectInstantiator { Object2ObjectOpenHashMap<Any, Any>() }

    kryo.register(ByteArray::class.java)
    kryo.register(ImmutableEntityFamily::class.java)
    kryo.register(RefsTable::class.java)
    kryo.register(ImmutableNonNegativeIntIntBiMap::class.java)
    kryo.register(ImmutableIntIntUniqueBiMap::class.java)
    kryo.register(VirtualFileIndex::class.java)
    kryo.register(EntityStorageInternalIndex::class.java)
    kryo.register(PersistentIdInternalIndex::class.java)
    kryo.register(ImmutableNonNegativeIntIntMultiMap.ByList::class.java)
    kryo.register(IntArray::class.java)
    kryo.register(Pair::class.java)
    kryo.register(MultimapStorageIndex::class.java)
    kryo.register(SerializableEntityId::class.java)

    kryo.register(ChangeEntry.AddEntity::class.java)
    kryo.register(ChangeEntry.RemoveEntity::class.java)
    kryo.register(ChangeEntry.ReplaceEntity::class.java)
    kryo.register(ChangeEntry.ChangeEntitySource::class.java)
    kryo.register(ChangeEntry.ReplaceAndChangeSource::class.java)

    registerFieldSerializer(kryo, Collections.unmodifiableCollection<Any>(emptySet()).javaClass) {
      Collections.unmodifiableCollection(emptySet())
    }
    registerFieldSerializer(kryo, Collections.unmodifiableSet<Any>(emptySet()).javaClass) { Collections.unmodifiableSet(emptySet()) }
    registerFieldSerializer(kryo, Collections.unmodifiableList<Any>(emptyList()).javaClass) { Collections.unmodifiableList(emptyList()) }
    registerFieldSerializer(kryo, Collections.unmodifiableMap<Any, Any>(emptyMap()).javaClass) { Collections.unmodifiableMap(emptyMap()) }

    kryo.register(Collections.EMPTY_LIST.javaClass, DefaultSerializers.CollectionsEmptyListSerializer())
    kryo.register(Collections.EMPTY_MAP.javaClass, DefaultSerializers.CollectionsEmptyMapSerializer())
    kryo.register(Collections.EMPTY_SET.javaClass, DefaultSerializers.CollectionsEmptySetSerializer())
    kryo.register(listOf(null).javaClass, DefaultSerializers.CollectionsSingletonListSerializer())
    kryo.register(Collections.singletonMap<Any, Any>(null, null).javaClass, DefaultSerializers.CollectionsSingletonMapSerializer())
    kryo.register(setOf(null).javaClass, DefaultSerializers.CollectionsSingletonSetSerializer())

    registerSingletonSerializer(kryo) { ContainerUtil.emptyList<Any>() }
    registerSingletonSerializer(kryo) { MostlySingularMultiMap.emptyMap<Any, Any>() }
    registerSingletonSerializer(kryo) { MultiMap.empty<Any, Any>() }

    registerSingletonSerializer(kryo) { emptyMap<Any, Any>() }
    registerSingletonSerializer(kryo) { emptyList<Any>() }
    registerSingletonSerializer(kryo) { emptySet<Any>() }
    registerSingletonSerializer(kryo) { emptyArray<Any>() }

    kryo.register(UUID::class.java)
    return kryo to classesCache
  }

  // TODO Dedup with OCSerializer
  private inline fun <reified T : Any> registerFieldSerializer(kryo: Kryo, type: Class<T> = T::class.java, crossinline create: () -> T) =
    registerSerializer(kryo, type, FieldSerializer(kryo, type)) { create() }

  @JvmSynthetic
  private inline fun registerSingletonSerializer(kryo: Kryo, crossinline getter: () -> Any) {
    val getter1 = ObjectInstantiator { getter() }
    registerSerializer(kryo, getter1.newInstance().javaClass, EmptySerializer, getter1)
  }

  object EmptySerializer : Serializer<Any>(false, true) {
    override fun write(kryo: Kryo?, output: Output?, `object`: Any?) {}
    override fun read(kryo: Kryo, input: Input?, type: Class<out Any>?): Any = kryo.newInstance(type)
  }

  private fun <T : Any> registerSerializer(kryo: Kryo, type: Class<T>, serializer: Serializer<in T>, initializer: ObjectInstantiator<T>) {
    kryo.register(type, serializer).apply { instantiator = initializer }
  }

  /**
   * Collect all classes existing in entity data.
   * [simpleClasses] - set of classes
   * [objectClasses] - set of kotlin objects
   */
  private fun recursiveClassFinder(kryo: Kryo,
                                   entity: Any,
                                   simpleClasses: MutableMap<TypeInfo, Class<out Any>>,
                                   objectClasses: MutableMap<TypeInfo, Class<out Any>>) {
    val jClass = entity.javaClass
    val classAlreadyRegistered = registerKClass(entity::class, jClass, kryo, objectClasses, simpleClasses)
    if (classAlreadyRegistered) return
    if (entity is VirtualFileUrl) return
    if (entity is Enum<*>) return

    ReflectionUtil.collectFields(jClass).forEach {
      val retType = it.type.name

      if ((retType.startsWith("kotlin") || retType.startsWith("java"))
          && !retType.startsWith("kotlin.collections.List")
          && !retType.startsWith("kotlin.collections.Set")
          && !retType.startsWith("kotlin.collections.Map")
          && !retType.startsWith("java.util.List")
          && !retType.startsWith("java.util.Set")
          && !retType.startsWith("java.util.Map")
      ) return@forEach

      it.trySetAccessible()
      if (Modifier.isStatic(it.modifiers) || !it.canAccess(entity)) return@forEach
      val property = ReflectionUtil.getFieldValue<Any>(it, entity) ?: run {
        registerKClass(it.type.kotlin, it.type, kryo, objectClasses, simpleClasses)
        return@forEach
      }
      if (property === entity) return@forEach
      registerKClass(property::class, property::class.java, kryo, objectClasses, simpleClasses)

      when (property) {
        is List<*> -> {
          val type = (it.genericType as ParameterizedType).actualTypeArguments[0] as? Class<*>
          if (type != null) {
            registerKClass(type.kotlin, type, kryo, objectClasses, simpleClasses)
          }

          property.filterNotNull().forEach { listItem ->
            recursiveClassFinder(kryo, listItem, simpleClasses, objectClasses)
          }
        }
        is Set<*> -> {
          val type = (it.genericType as ParameterizedType).actualTypeArguments[0] as? Class<*>
          if (type != null) {
            registerKClass(type.kotlin, type, kryo, objectClasses, simpleClasses)
          }

          property.filterNotNull().forEach { setItem ->
            recursiveClassFinder(kryo, setItem, simpleClasses, objectClasses)
          }
        }
        is Map<*, *> -> {
          val actualTypeArguments = (it.genericType as ParameterizedType).actualTypeArguments
          val keyType = actualTypeArguments[0] as? Class<*>
          val valueType = actualTypeArguments[1] as? Class<*>
          if (keyType != null) {
            registerKClass(keyType.kotlin, keyType, kryo, objectClasses, simpleClasses)
          }
          if (valueType != null) {
            registerKClass(valueType.kotlin, valueType, kryo, objectClasses, simpleClasses)
          }

          property.forEach { key, value ->
            if (key != null) {
              recursiveClassFinder(kryo, key, simpleClasses, objectClasses)
            }
            if (value != null) {
              recursiveClassFinder(kryo, value, simpleClasses, objectClasses)
            }
          }
        }
        is Array<*> -> {
          val type = (it.genericType as Class<*>).componentType
          if (type != null) {
            registerKClass(type.kotlin, type, kryo, objectClasses, simpleClasses)
          }

          property.filterNotNull().forEach { listItem ->
            recursiveClassFinder(kryo, listItem, simpleClasses, objectClasses)
          }
        }
        else -> {
          recursiveClassFinder(kryo, property, simpleClasses, objectClasses)
        }
      }
    }
  }

  private fun registerKClass(kClass: KClass<out Any>,
                             jClass: Class<out Any>,
                             kryo: Kryo,
                             objectClasses: MutableMap<TypeInfo, Class<out Any>>,
                             simpleClasses: MutableMap<TypeInfo, Class<out Any>>): Boolean {
    val typeInfo = jClass.typeInfo
    if (kryo.classResolver.getRegistration(jClass) != null) return true

    val objectInstance = kClass.objectInstance
    if (objectInstance != null) {
      objectClasses[typeInfo] = jClass
    }
    else {
      simpleClasses[typeInfo] = jClass
    }
    return false
  }

  override fun serializeCache(stream: OutputStream, storage: EntityStorageSnapshot): SerializationResult {
    storage as EntityStorageSnapshotImpl

    val output = Output(stream, KRYO_BUFFER_SIZE)
    return try {
      val (kryo, _) = createKryo()

      // Save version
      output.writeString(serializerDataFormatVersion)

      saveContributedVersions(kryo, output)

      var newCacheType = Registry.`is`("ide.workspace.model.generated.code.for.cache", true)
      if (newCacheType) {
        try {
          collectAndRegisterClasses(kryo, output, storage)
        }
        catch (e: NotGeneratedRuntimeException) {
          LOG.error(e)
          newCacheType = false
        }
      }
      if (!newCacheType) {
        val entityDataSequence = storage.entitiesByType.entityFamilies.filterNotNull().asSequence().flatMap { family ->
          family.entities.asSequence().filterNotNull()
        }
        collectAndRegisterClasses(kryo, output, entityDataSequence)
      }


      // Serialize and register persistent ids
      val persistentIdClasses = storage.indexes.persistentIdIndex.entries().mapTo(HashSet()) { it::class.java }
      output.writeVarInt(persistentIdClasses.size, true)
      persistentIdClasses.forEach {
        val typeInfo = it.typeInfo
        kryo.register(it)
        kryo.writeClassAndObject(output, typeInfo)
      }

      // Write entity data and references
      kryo.writeClassAndObject(output, storage.entitiesByType)
      kryo.writeObject(output, storage.refs)

      // Write indexes
      kryo.writeObject(output, storage.indexes.softLinks)

      kryo.writeObject(output, storage.indexes.virtualFileIndex.entityId2VirtualFileUrl)
      kryo.writeObject(output, storage.indexes.virtualFileIndex.vfu2EntityId)
      kryo.writeObject(output, storage.indexes.virtualFileIndex.entityId2JarDir)

      kryo.writeObject(output, storage.indexes.entitySourceIndex)
      kryo.writeObject(output, storage.indexes.persistentIdIndex)

      SerializationResult.Success
    }
    catch (e: Exception) {
      output.reset()
      LOG.warn("Exception at project serialization", e)
      SerializationResult.Fail(e.message)
    }
    finally {
      flush(output)
    }
  }

  internal fun flush(output: Output) {
    try {
      output.flush()
    }
    catch (e: KryoException) {
      output.reset()
      LOG.warn("Exception at project serialization", e)
      SerializationResult.Fail(e.message)
    }
  }

  private fun collectAndRegisterClasses(kryo: Kryo, output: Output, entityStorage: EntityStorageSnapshotImpl) {
    val collector = UsedClassesCollector()
    for (entityFamily in entityStorage.entitiesByType.entityFamilies) {
      entityFamily?.entities?.forEach { entity ->
        if (entity == null) return@forEach
        collector.add(entity::class.java)
        entity.collectClassUsagesData(collector)
        if (collector.sameForAllEntities) {
          return@forEach
        }
      }
    }

    val simpleClasses = HashMap<TypeInfo, Class<out Any>>()
    val objectClasses = HashMap<TypeInfo, Class<out Any>>()
    entityStorage.indexes.entitySourceIndex.entries().forEach {
      collector.add(it::class.java)
      recursiveClassFinder(kryo, it, simpleClasses, objectClasses)
    }
    simpleClasses.forEach { collector.add(it.value) }
    objectClasses.forEach { collector.add(it.value) }

    entityStorage.indexes.virtualFileIndex.vfu2EntityId.forEach { virtualFileUrl, object2LongOpenHashMap ->
      collector.add(virtualFileUrl::class.java)
    }

    output.writeVarInt(collector.collectionObjects.size, true)
    collector.collectionObjects.forEach { clazz ->
      kryo.register(clazz)
      // TODO switch to only write object
      kryo.writeClassAndObject(output, clazz.typeInfo)
    }

    output.writeVarInt(collector.collection.size, true)
    collector.collection.forEach { clazz ->
      kryo.register(clazz)
      // TODO switch to only write object
      kryo.writeClassAndObject(output, clazz.typeInfo)
    }
  }

  internal fun collectAndRegisterClasses(kryo: Kryo, output: Output, entityDataSequence: Sequence<WorkspaceEntityData<*>>) {
    // Collect all classes existing in entity data
    val simpleClasses = HashMap<TypeInfo, Class<out Any>>()
    val objectClasses = HashMap<TypeInfo, Class<out Any>>()
    entityDataSequence.forEach { recursiveClassFinder(kryo, it, simpleClasses, objectClasses) }

    // Serialize and register types of kotlin objects
    output.writeVarInt(objectClasses.size, true)
    objectClasses.forEach {
      kryo.register(it.value)
      kryo.writeClassAndObject(output, it.key)
    }

    // Serialize and register all types existing in entity data
    output.writeVarInt(simpleClasses.size, true)
    simpleClasses.forEach {
      kryo.register(it.value)
      kryo.writeClassAndObject(output, it.key)
    }
  }

  override fun deserializeCache(stream: InputStream): Result<MutableEntityStorage?> {
    LOG.debug("Start deserializing workspace model cache")
    val deserializedCache = Input(stream, KRYO_BUFFER_SIZE).use { input ->
      val (kryo, classesCache) = createKryo()

      try { // Read version
        measureNanoTime {
          val cacheVersion = input.readString()
          if (cacheVersion != serializerDataFormatVersion) {
            LOG.info("Cache isn't loaded. Current version of cache: $serializerDataFormatVersion, version of cache file: $cacheVersion")
            return Result.success(null)
          }

          if (!checkContributedVersion(kryo, input)) return Result.success(null)
        }.also { LOG.debug("Load version and contributed versions: $it ns") }

        measureNanoTime {
          readAndRegisterClasses(input, kryo, classesCache)
        }.also { LOG.debug("Read and register classes: $it ns") }

        // Read and register persistent ids
        measureNanoTime {
          val persistentIdCount = input.readVarInt(true)
          repeat(persistentIdCount) {
            val objectClass = kryo.readClassAndObject(input) as TypeInfo
            val resolvedClass = typesResolver.resolveClass(objectClass.name, objectClass.pluginId)
            kryo.register(resolvedClass)
            classesCache.putIfAbsent(objectClass, resolvedClass.toClassId())
          }
        }.also { LOG.debug("Read and register persistent ids: $it ns") }

        var time = System.nanoTime()

        // Read entity data and references
        val entitiesBarrel = kryo.readClassAndObject(input) as ImmutableEntitiesBarrel
        val refsTable = kryo.readObject(input, RefsTable::class.java)

        LOG.debug("Read data and references: " + (System.nanoTime() - time) + " ns")
        time = System.nanoTime()

        // Read indexes
        val softLinks = kryo.readObject(input, MultimapStorageIndex::class.java)

        LOG.debug("Read soft links: " + (System.nanoTime() - time) + " ns")
        time = System.nanoTime()

        val entityId2VirtualFileUrlInfo = kryo.readObject(input, Long2ObjectOpenHashMap::class.java) as Long2ObjectOpenHashMap<Any>
        val vfu2VirtualFileUrlInfo = kryo.readObject(input,
                                                     Object2ObjectOpenCustomHashMap::class.java) as Object2ObjectOpenCustomHashMap<VirtualFileUrl, Object2LongOpenHashMap<String>>
        val entityId2JarDir = kryo.readObject(input, BidirectionalLongMultiMap::class.java) as BidirectionalLongMultiMap<VirtualFileUrl>

        val virtualFileIndex = VirtualFileIndex(entityId2VirtualFileUrlInfo, vfu2VirtualFileUrlInfo, entityId2JarDir)

        LOG.debug("Read virtual file index: " + (System.nanoTime() - time) + " ns")
        time = System.nanoTime()

        val entitySourceIndex = kryo.readObject(input, EntityStorageInternalIndex::class.java) as EntityStorageInternalIndex<EntitySource>

        LOG.debug("Read virtual file index: " + (System.nanoTime() - time) + " ns")
        time = System.nanoTime()

        val persistentIdIndex = kryo.readObject(input, PersistentIdInternalIndex::class.java)

        LOG.debug("Persistent id index: " + (System.nanoTime() - time) + " ns")

        val storageIndexes = StorageIndexes(softLinks, virtualFileIndex, entitySourceIndex, persistentIdIndex)

        val storage = EntityStorageSnapshotImpl(entitiesBarrel, refsTable, storageIndexes)
        val builder = MutableEntityStorageImpl.from(storage)

        builder.entitiesByType.entityFamilies.forEach { family ->
          family?.entities?.asSequence()?.filterNotNull()?.forEach { entityData -> builder.createAddEvent(entityData) }
        }

        if (LOG.isTraceEnabled) {
          builder.assertConsistency()
          LOG.trace("Builder loaded from caches has no consistency issues")
        }

        builder
      }
      catch (e: Exception) {
        return Result.failure(e)
      }
    }
    return Result.success(deserializedCache)
  }

  private fun checkContributedVersion(kryo: Kryo, input: Input): Boolean {
    @Suppress("UNCHECKED_CAST")
    val cacheContributedVersions = kryo.readClassAndObject(input) as Map<String, String>
    val currentContributedVersions = versionsContributor()
    for ((id, version) in cacheContributedVersions) {
      // Cache is invalid in case:
      // - current version != cache version
      // cache version is missing in current version
      //
      // If some version that currently exists but missing in cache, cache is not treated as invalid
      val currentVersion = currentContributedVersions[id] ?: run {
        LOG.info("Cache isn't loaded. Cache id '$id' is missing in current state")
        return false
      }
      if (currentVersion != version) {
        LOG.info("Cache isn't loaded. For cache id '$id' cache version is '$version' and current versioni is '$currentVersion'")
        return false
      }
    }
    return true
  }

  internal fun saveContributedVersions(kryo: Kryo, output: Output) {
    // Save contributed versions
    val versions = versionsContributor()
    kryo.writeClassAndObject(output, versions)
  }

  private fun readAndRegisterClasses(input: Input, kryo: Kryo, classCache: MutableMap<TypeInfo, Int>) {
    // Read and register all kotlin objects
    val objectCount = input.readVarInt(true)
    repeat(objectCount) {
      val objectClass = kryo.readClassAndObject(input) as TypeInfo
      registerSingletonSerializer(kryo) {
        val resolvedClass = typesResolver.resolveClass(objectClass.name, objectClass.pluginId)
        classCache.putIfAbsent(objectClass, resolvedClass.toClassId())
        resolvedClass.kotlin.objectInstance!!
      }
    }

    // Read and register all types in entity data
    val nonObjectCount = input.readVarInt(true)
    repeat(nonObjectCount) {
      val objectClass = kryo.readClassAndObject(input) as TypeInfo
      val resolvedClass = typesResolver.resolveClass(objectClass.name, objectClass.pluginId)
      classCache.putIfAbsent(objectClass, resolvedClass.toClassId())
      kryo.register(resolvedClass)
    }
  }

  private inner class ObjectOpenHashSetSerializer : Serializer<ObjectOpenHashSet<*>>(false, true) {
    override fun write(kryo: Kryo, output: Output, `object`: ObjectOpenHashSet<*>) {
      output.writeInt(`object`.size)
      `object`.forEach { kryo.writeClassAndObject(output, it) }
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out ObjectOpenHashSet<*>>): ObjectOpenHashSet<*> {
      val res = ObjectOpenHashSet<Any>()
      repeat(input.readInt()) {
        val data = kryo.readClassAndObject(input)
        res.add(data)
      }
      return res
    }
  }

  private inner class ParentEntityIdSerializer(val classesCache: MutableMap<TypeInfo, Int>) : Serializer<ParentEntityId>(false, true) {
    override fun write(kryo: Kryo, output: Output, `object`: ParentEntityId) {
      kryo.writeClassAndObject(output, `object`.id.toSerializableEntityId())
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out ParentEntityId>): ParentEntityId {
      val entityId = kryo.readClassAndObject(input) as SerializableEntityId
      return ParentEntityId(entityId.toEntityId(classesCache))
    }
  }

  private inner class ChildEntityIdSerializer(val classesCache: MutableMap<TypeInfo, Int>) : Serializer<ChildEntityId>(false, true) {
    override fun write(kryo: Kryo, output: Output, `object`: ChildEntityId) {
      kryo.writeClassAndObject(output, `object`.id.toSerializableEntityId())
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out ChildEntityId>): ChildEntityId {
      val entityId = kryo.readClassAndObject(input) as SerializableEntityId
      return ChildEntityId(entityId.toEntityId(classesCache))
    }
  }

  private inner class ImmutableEntitiesBarrelSerializer(private val classesCache: MutableMap<TypeInfo, Int>)
    : Serializer<ImmutableEntitiesBarrel>(false, true) {
    override fun write(kryo: Kryo, output: Output, `object`: ImmutableEntitiesBarrel) {
      val res = HashMap<TypeInfo, EntityFamily<*>>()
      `object`.entityFamilies.forEachIndexed { i, v ->
        if (v == null) return@forEachIndexed
        val clazz = i.findEntityClass<WorkspaceEntity>()
        val typeInfo = clazz.typeInfo
        res[typeInfo] = v
      }
      kryo.writeClassAndObject(output, res)
    }

    @Suppress("UNCHECKED_CAST")
    override fun read(kryo: Kryo, input: Input, type: Class<out ImmutableEntitiesBarrel>): ImmutableEntitiesBarrel {
      val mutableBarrel = MutableEntitiesBarrel.create()
      val families = kryo.readClassAndObject(input) as HashMap<TypeInfo, EntityFamily<*>>
      for ((typeInfo, family) in families) {
        val classId = classesCache.getOrPut(typeInfo) { typesResolver.resolveClass(typeInfo.name, typeInfo.pluginId).toClassId() }
        mutableBarrel.fillEmptyFamilies(classId)
        mutableBarrel.entityFamilies[classId] = family
      }
      return mutableBarrel.toImmutable()
    }
  }

  private inner class ConnectionIdSerializer(private val classesCache: MutableMap<TypeInfo, Int>) : Serializer<ConnectionId>(false, true) {
    override fun write(kryo: Kryo, output: Output, `object`: ConnectionId) {
      val parentClassType = `object`.parentClass.findEntityClass<WorkspaceEntity>()
      val childClassType = `object`.childClass.findEntityClass<WorkspaceEntity>()
      val parentTypeInfo = parentClassType.typeInfo
      val childTypeInfo = childClassType.typeInfo

      kryo.writeClassAndObject(output, parentTypeInfo)
      kryo.writeClassAndObject(output, childTypeInfo)
      output.writeString(`object`.connectionType.name)
      output.writeBoolean(`object`.isParentNullable)
    }

    @Suppress("UNCHECKED_CAST")
    override fun read(kryo: Kryo, input: Input, type: Class<out ConnectionId>): ConnectionId {
      val parentClazzInfo = kryo.readClassAndObject(input) as TypeInfo
      val childClazzInfo = kryo.readClassAndObject(input) as TypeInfo

      val parentClass = classesCache.getOrPut(parentClazzInfo) {
        (typesResolver.resolveClass(parentClazzInfo.name, parentClazzInfo.pluginId) as Class<WorkspaceEntity>).toClassId()
      }
      val childClass = classesCache.getOrPut(childClazzInfo) {
        (typesResolver.resolveClass(childClazzInfo.name, childClazzInfo.pluginId) as Class<WorkspaceEntity>).toClassId()
      }

      val connectionType = ConnectionId.ConnectionType.valueOf(input.readString())
      val parentNullable = input.readBoolean()
      return ConnectionId.create(parentClass, childClass, connectionType, parentNullable)
    }
  }

  private inner class HashMultimapSerializer : Serializer<HashMultimap<*, *>>(false, true) {
    override fun write(kryo: Kryo, output: Output, `object`: HashMultimap<*, *>) {
      val res = HashMap<Any?, Collection<Any?>>()
      `object`.asMap().forEach { (key, values) ->
        res[key] = ArrayList(values)
      }
      kryo.writeClassAndObject(output, res)
    }

    @Suppress("UNCHECKED_CAST")
    override fun read(kryo: Kryo, input: Input, type: Class<out HashMultimap<*, *>>): HashMultimap<*, *> {
      val res = HashMultimap.create<Any, Any>()
      val map = kryo.readClassAndObject(input) as HashMap<*, Collection<*>>
      map.forEach { (key, values) ->
        res.putAll(key, values)
      }
      return res
    }
  }

  private inner class EntityIdSerializer(val classesCache: MutableMap<TypeInfo, Int>) : Serializer<EntityId>(false, true) {
    override fun write(kryo: Kryo, output: Output, `object`: EntityId) {
      output.writeInt(`object`.arrayId)
      val typeClass = `object`.clazz.findEntityClass<WorkspaceEntity>()
      val typeInfo = typeClass.typeInfo
      kryo.writeClassAndObject(output, typeInfo)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out EntityId>): EntityId {
      val arrayId = input.readInt()
      val clazzInfo = kryo.readClassAndObject(input) as TypeInfo
      val clazz = classesCache.getOrPut(clazzInfo) { typesResolver.resolveClass(clazzInfo.name, clazzInfo.pluginId).toClassId() }
      return createEntityId(arrayId, clazz)
    }
  }

  private inner class VirtualFileUrlSerializer : Serializer<VirtualFileUrl>(false, true) {
    override fun write(kryo: Kryo, output: Output, obj: VirtualFileUrl) {
      // TODO Write IDs only
      kryo.writeObject(output, obj.urlSegments)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out VirtualFileUrl>): VirtualFileUrl {
      val url = kryo.readObject(input, List::class.java) as List<String>
      return virtualFileManager.fromUrlSegments(url)
    }
  }

  private inner class PersistentIdIndexSerializer(private val classesCache: MutableMap<TypeInfo, Int>) : Serializer<PersistentIdInternalIndex>(
    false, true) {
    override fun write(kryo: Kryo, output: Output, persistenIndex: PersistentIdInternalIndex) {
      output.writeInt(persistenIndex.index.keys.size)
      persistenIndex.index.forEach { key, value ->
        kryo.writeObject(output, key.toSerializableEntityId())
        kryo.writeClassAndObject(output, value)
      }
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out PersistentIdInternalIndex>): PersistentIdInternalIndex {
      val res = PersistentIdInternalIndex.MutablePersistentIdInternalIndex.from(PersistentIdInternalIndex())
      repeat(input.readInt()) {
        val key = kryo.readObject(input, SerializableEntityId::class.java).toEntityId(classesCache)
        val value = kryo.readClassAndObject(input) as PersistentEntityId<*>
        res.index(key, value)
      }
      return res.toImmutable()
    }
  }

  private inner class EntityStorageIndexSerializer(private val classesCache: MutableMap<TypeInfo, Int>) : Serializer<EntityStorageInternalIndex<EntitySource>>(
    false, true) {
    override fun write(kryo: Kryo, output: Output, entityStorageIndex: EntityStorageInternalIndex<EntitySource>) {
      output.writeInt(entityStorageIndex.index.keys.size)
      entityStorageIndex.index.forEach { entry ->
        kryo.writeObject(output, entry.longKey.toSerializableEntityId())
        kryo.writeClassAndObject(output, entry.value)
      }
    }

    override fun read(kryo: Kryo,
                      input: Input,
                      type: Class<out EntityStorageInternalIndex<EntitySource>>): EntityStorageInternalIndex<EntitySource> {
      val res = EntityStorageInternalIndex.MutableEntityStorageInternalIndex.from(EntityStorageInternalIndex<EntitySource>(false))
      repeat(input.readInt()) {
        val key = kryo.readObject(input, SerializableEntityId::class.java).toEntityId(classesCache)
        val value = kryo.readClassAndObject(input) as EntitySource
        res.index(key, value)
      }
      return res.toImmutable()
    }
  }

  private inner class EntityId2JarDirSerializer(private val classesCache: MutableMap<TypeInfo, Int>) : Serializer<EntityId2JarDir>() {
    override fun write(kryo: Kryo, output: Output, entityId2JarDir: EntityId2JarDir) {
      output.writeInt(entityId2JarDir.keys.size)
      entityId2JarDir.keys.forEach { key ->
        val values: Set<VirtualFileUrl> = entityId2JarDir.getValues(key)
        kryo.writeObject(output, key.toSerializableEntityId())
        output.writeInt(values.size)
        for (value in values) {
          kryo.writeObject(output, value)
        }
      }
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out EntityId2JarDir>): EntityId2JarDir {
      val res = EntityId2JarDir()
      repeat(input.readInt()) {
        val key = kryo.readObject(input, SerializableEntityId::class.java).toEntityId(classesCache)
        repeat(input.readInt()) {
          res.put(key, kryo.readObject(input, VirtualFileUrl::class.java))
        }
      }
      return res
    }
  }

  private inner class Vfu2EntityIdSerializer(private val classesCache: MutableMap<TypeInfo, Int>) : Serializer<Vfu2EntityId>() {
    override fun write(kryo: Kryo, output: Output, vfu2EntityId: Vfu2EntityId) {
      output.writeInt(vfu2EntityId.keys.size)
      vfu2EntityId.forEach { (key: VirtualFileUrl, value) ->
        kryo.writeObject(output, key)
        output.writeInt(value.keys.size)
        value.forEach { (internalKey: String, internalValue) ->
          output.writeString(internalKey)
          kryo.writeObject(output, internalValue.toSerializableEntityId())
        }
      }
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out Vfu2EntityId>): Vfu2EntityId {
      val vfu2EntityId = Vfu2EntityId(getHashingStrategy())
      repeat(input.readInt()) {
        val file = kryo.readObject(input, VirtualFileUrl::class.java) as VirtualFileUrl
        val data = Object2LongOpenHashMap<String>()
        repeat(input.readInt()) {
          val internalKey = input.readString()
          val entityId = kryo.readObject(input, SerializableEntityId::class.java).toEntityId(classesCache)
          data[internalKey] = entityId
        }
        vfu2EntityId[file] = data
      }
      return vfu2EntityId
    }
  }

  private inner class EntityId2VfuSerializer(private val classesCache: MutableMap<TypeInfo, Int>) : Serializer<EntityId2Vfu>() {
    override fun write(kryo: Kryo, output: Output, entityId2Vfu: EntityId2Vfu) {
      kryo.writeClassAndObject(output, entityId2Vfu.mapKeys { it.key.toSerializableEntityId() })
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out EntityId2Vfu>): EntityId2Vfu {
      val data = kryo.readClassAndObject(input) as Map<SerializableEntityId, Any>
      return EntityId2Vfu(data.size).also {
        data.forEach { key, value ->
          it.put(key.toEntityId(classesCache), value)
        }
      }
    }
  }

  private inner class MultimapStorageIndexSerializer(private val classesCache: MutableMap<TypeInfo, Int>) : Serializer<MultimapStorageIndex>() {
    override fun write(kryo: Kryo, output: Output, multimapIndex: MultimapStorageIndex) {
      kryo.writeClassAndObject(output, multimapIndex.toMap().mapKeys { it.key.toSerializableEntityId() })
    }

    override fun read(kryo: Kryo, input: Input?, type: Class<out MultimapStorageIndex>): MultimapStorageIndex {
      val data = kryo.readClassAndObject(input) as Map<SerializableEntityId, Set<PersistentEntityId<*>>>
      val index = MultimapStorageIndex.MutableMultimapStorageIndex.from(MultimapStorageIndex())
      data.forEach { (key, value) ->
        index.index(key.toEntityId(classesCache), value)
      }
      return index
    }
  }

  internal data class TypeInfo(val name: String, val pluginId: String?)

  internal val Class<*>.typeInfo: TypeInfo
    get() = typeInfoInterner.intern(TypeInfo(name, typesResolver.getPluginId(this)))

  private data class SerializableEntityId(val arrayId: Int, val type: TypeInfo)

  private fun EntityId.toSerializableEntityId(): SerializableEntityId {
    val arrayId = this.arrayId
    val clazz = this.clazz.findEntityClass<WorkspaceEntity>()
    return interner.intern(SerializableEntityId(arrayId, clazz.typeInfo))
  }

  private fun SerializableEntityId.toEntityId(classesCache: MutableMap<TypeInfo, Int>): EntityId {
    val classId = classesCache.getOrPut(type) { typesResolver.resolveClass(this.type.name, this.type.pluginId).toClassId() }
    return createEntityId(this.arrayId, classId)
  }

  @TestOnly
  @Suppress("UNCHECKED_CAST")
  fun deserializeCacheAndDiffLog(storeStream: InputStream, diffLogStream: InputStream): MutableEntityStorage? {
    val builder = this.deserializeCache(storeStream).getOrThrow() ?: return null

    var log: ChangeLog
    Input(diffLogStream, KRYO_BUFFER_SIZE).use { input ->
      val (kryo, classCache) = createKryo()

      // Read version
      val cacheVersion = input.readString()
      if (cacheVersion != serializerDataFormatVersion) {
        LOG.info("Cache isn't loaded. Current version of cache: $serializerDataFormatVersion, version of cache file: $cacheVersion")
        return null
      }

      if (!checkContributedVersion(kryo, input)) return null

      readAndRegisterClasses(input, kryo, classCache)

      log = kryo.readClassAndObject(input) as ChangeLog
    }

    builder as MutableEntityStorageImpl
    builder.changeLog.changeLog.clear()
    builder.changeLog.changeLog.putAll(log)

    return builder
  }

  @TestOnly
  @Suppress("UNCHECKED_CAST")
  fun deserializeClassToIntConverter(stream: InputStream) {
    Input(stream, KRYO_BUFFER_SIZE).use { input ->
      val (kryo, _) = createKryo()

      // Read version
      val cacheVersion = input.readString()
      if (cacheVersion != serializerDataFormatVersion) {
        LOG.info("Cache isn't loaded. Current version of cache: $serializerDataFormatVersion, version of cache file: $cacheVersion")
        return
      }

      if (!checkContributedVersion(kryo, input)) return

      val classes = kryo.readClassAndObject(input) as List<Pair<TypeInfo, Int>>
      val map = classes.map { (first, second) -> typesResolver.resolveClass(first.name, first.pluginId) to second }.toMap()
      ClassToIntConverter.INSTANCE.fromMap(map)
    }
  }
}
