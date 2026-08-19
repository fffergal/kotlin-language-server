package org.javacs.kt.classpath

import org.javacs.kt.LOG
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val MAX_PATH_LENGTH = 2047
private const val MAX_IDENTITY_LENGTH = 2047
private const val MAX_FINGERPRINT_LENGTH = 8192

private object ClassPathMetadataCache : IntIdTable() {
    val projectIdentity = varchar("projectidentity", MAX_IDENTITY_LENGTH).uniqueIndex()
    val fingerprint = varchar("fingerprint", MAX_FINGERPRINT_LENGTH)
    val includesSources = bool("includessources")
}

private object ClassPathCacheEntry : IntIdTable() {
    val projectIdentity = varchar("projectidentity", MAX_IDENTITY_LENGTH).index()
    val compiledJar = varchar("compiledjar", MAX_PATH_LENGTH)
    val sourceJar = varchar("sourcejar", MAX_PATH_LENGTH).nullable()
}

private object BuildScriptClassPathCacheEntry : IntIdTable() {
    val projectIdentity = varchar("projectidentity", MAX_IDENTITY_LENGTH).index()
    val jar = varchar("jar", MAX_PATH_LENGTH)
}

class ClassPathMetadataCacheEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ClassPathMetadataCacheEntity>(ClassPathMetadataCache)
    var projectIdentity by ClassPathMetadataCache.projectIdentity
    var fingerprint by ClassPathMetadataCache.fingerprint
    var includesSources by ClassPathMetadataCache.includesSources
}

class ClassPathCacheEntryEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ClassPathCacheEntryEntity>(ClassPathCacheEntry)
    var projectIdentity by ClassPathCacheEntry.projectIdentity
    var compiledJar by ClassPathCacheEntry.compiledJar
    var sourceJar by ClassPathCacheEntry.sourceJar
}

class BuildScriptClassPathCacheEntryEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<BuildScriptClassPathCacheEntryEntity>(BuildScriptClassPathCacheEntry)
    var projectIdentity by BuildScriptClassPathCacheEntry.projectIdentity
    var jar by BuildScriptClassPathCacheEntry.jar
}

/** Persists one resolver's coherent project model without affecting other projects. */
internal class CachedClassPathResolver(
    private val wrapped: ClassPathResolver,
    private val db: Database,
    private val projectIdentity: String = requireNotNull(wrapped.cacheIdentity)
) : ClassPathResolver {
    override val resolverType: String get() = "Cached + ${wrapped.resolverType}"
    override val cacheIdentity: String get() = projectIdentity
    override val freshnessFingerprint: String get() = wrapped.freshnessFingerprint
    override val currentBuildFileVersion: Long get() = wrapped.currentBuildFileVersion

    init {
        transaction(db) {
            SchemaUtils.createMissingTablesAndColumns(
                ClassPathMetadataCache, ClassPathCacheEntry, BuildScriptClassPathCacheEntry
            )
        }
    }

    override val classpath: Set<ClassPathEntry> get() = resolve().classpath
    override val classpathWithSources: Set<ClassPathEntry> get() = resolve().classpath
    override val buildScriptClasspath: Set<Path> get() = resolve().buildScriptClasspath

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    @Synchronized
    override fun resolve(): ResolvedClassPath {
        val cached = readCachedModel()
        if (cached != null && cached.fingerprint == freshnessFingerprint && cached.pathsExist()) {
            LOG.info("Loading fresh project model for '{}' from persistent cache", projectIdentity)
            ClassPathInstrumentation.persistentCacheHit(projectIdentity)
            return cached.model
        }

        ClassPathInstrumentation.underlyingResolution(projectIdentity)
        val resolved = try {
            wrapped.resolve()
        } catch (error: Exception) {
            if (cached != null) {
                LOG.warn("Resolution for '{}' failed; retaining cached project model: {}", projectIdentity, error.message)
                return cached.model
            }
            throw error
        }

        if (resolved.classpath.isEmpty()) {
            if (cached != null) {
                LOG.warn("Resolution for '{}' was empty; retaining cached project model", projectIdentity)
                return cached.model
            }
            return resolved
        }

        writeModel(resolved)
        return resolved
    }

    private fun readCachedModel(): CachedModel? = transaction(db) {
        val metadata = ClassPathMetadataCache.select { ClassPathMetadataCache.projectIdentity eq projectIdentity }
            .firstOrNull() ?: return@transaction null
        val classpath = ClassPathCacheEntry.select { ClassPathCacheEntry.projectIdentity eq projectIdentity }
            .map { ClassPathEntry(Paths.get(it[ClassPathCacheEntry.compiledJar]), it[ClassPathCacheEntry.sourceJar]?.let(Paths::get)) }
            .toSet()
        if (classpath.isEmpty()) return@transaction null
        val buildScripts = BuildScriptClassPathCacheEntry.select {
            BuildScriptClassPathCacheEntry.projectIdentity eq projectIdentity
        }
            .map { Paths.get(it[BuildScriptClassPathCacheEntry.jar]) }
            .toSet()
        CachedModel(metadata[ClassPathMetadataCache.fingerprint], ResolvedClassPath(classpath, buildScripts))
    }

    private fun writeModel(model: ResolvedClassPath) = transaction(db) {
        ClassPathCacheEntry.deleteWhere { ClassPathCacheEntry.projectIdentity eq this@CachedClassPathResolver.projectIdentity }
        BuildScriptClassPathCacheEntry.deleteWhere { BuildScriptClassPathCacheEntry.projectIdentity eq this@CachedClassPathResolver.projectIdentity }
        ClassPathMetadataCache.deleteWhere { ClassPathMetadataCache.projectIdentity eq this@CachedClassPathResolver.projectIdentity }

        model.classpath.forEach { entry ->
            ClassPathCacheEntry.insert {
                it[projectIdentity] = this@CachedClassPathResolver.projectIdentity
                it[compiledJar] = entry.compiledJar.toString()
                it[sourceJar] = entry.sourceJar?.toString()
            }
        }
        model.buildScriptClasspath.forEach { entry ->
            BuildScriptClassPathCacheEntry.insert {
                it[projectIdentity] = this@CachedClassPathResolver.projectIdentity
                it[jar] = entry.toString()
            }
        }
        ClassPathMetadataCache.insert {
            it[projectIdentity] = this@CachedClassPathResolver.projectIdentity
            it[fingerprint] = freshnessFingerprint
            it[includesSources] = true
        }
    }

    private data class CachedModel(val fingerprint: String, val model: ResolvedClassPath) {
        fun pathsExist(): Boolean = model.classpath.all {
            Files.exists(it.compiledJar) && (it.sourceJar == null || Files.exists(it.sourceJar))
        } && model.buildScriptClasspath.all(Files::exists)
    }
}
