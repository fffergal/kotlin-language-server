package org.javacs.kt.classpath

import org.javacs.kt.LOG
import java.nio.file.Path
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.max

/** One coherent result from a single resolver freshness state. */
data class ResolvedClassPath(
    val classpath: Set<ClassPathEntry>,
    val buildScriptClasspath: Set<Path> = emptySet()
)

/** A source for creating class paths */
interface ClassPathResolver {
    val resolverType: String

    /** Stable identity used to isolate persistent project models. */
    val cacheIdentity: String? get() = null

    /** Changes whenever inputs affecting this resolver change. */
    val freshnessFingerprint: String get() = currentBuildFileVersion.toString()

    val classpath: Set<ClassPathEntry> // may throw exceptions
    val classpathOrEmpty: Set<ClassPathEntry> // does not throw exceptions
        get() = try {
            classpath
        } catch (e: Exception) {
            LOG.warn("Could not resolve classpath using {}: {}", resolverType, e.message)
            emptySet<ClassPathEntry>()
        }

    val buildScriptClasspath: Set<Path>
        get() = emptySet<Path>()
    val buildScriptClasspathOrEmpty: Set<Path>
        get() = try {
            buildScriptClasspath
        } catch (e: Exception) {
            LOG.warn("Could not resolve buildscript classpath using {}: {}", resolverType, e.message)
            emptySet<Path>()
        }

    val classpathWithSources: Set<ClassPathEntry> get() = classpath

    /** Resolves all classpath views as one model. Implementations should override when discovery is expensive. */
    fun resolve(): ResolvedClassPath = ResolvedClassPath(classpathWithSources, buildScriptClasspathOrEmpty)

    /**
     * This should return the current build file version.
     * It usually translates to the file's lastModified time.
     * Resolvers that don't have a build file use the default (i.e., 1).
     * We use 1, because this will prevent any attempt to cache non cacheable resolvers
     * (see [CachedClassPathResolver.dependenciesChanged]).
     */
    val currentBuildFileVersion: Long
        get() = 1L

    companion object {
        /** A default empty classpath implementation */
        val empty = object : ClassPathResolver {
            override val resolverType = "[]"
            override val classpath = emptySet<ClassPathEntry>()
        }
    }
}

val Sequence<ClassPathResolver>.joined get() = fold(ClassPathResolver.empty) { accum, next -> accum + next }

val Collection<ClassPathResolver>.joined get() = fold(ClassPathResolver.empty) { accum, next -> accum + next }

/** Combines two classpath resolvers. */
operator fun ClassPathResolver.plus(other: ClassPathResolver): ClassPathResolver = UnionClassPathResolver(this, other)

/** Uses the left-hand classpath if not empty, otherwise uses the right. */
infix fun ClassPathResolver.or(other: ClassPathResolver): ClassPathResolver = FirstNonEmptyClassPathResolver(this, other)

/** The union of two class path resolvers. */
internal class UnionClassPathResolver(val lhs: ClassPathResolver, val rhs: ClassPathResolver) : ClassPathResolver {
    override val resolverType: String get() = "(${lhs.resolverType} + ${rhs.resolverType})"
    override val classpath get() = lhs.classpath + rhs.classpath
    override val classpathOrEmpty get() = lhs.classpathOrEmpty + rhs.classpathOrEmpty
    override val buildScriptClasspath get() = lhs.buildScriptClasspath + rhs.buildScriptClasspath
    override val buildScriptClasspathOrEmpty get() = lhs.buildScriptClasspathOrEmpty + rhs.buildScriptClasspathOrEmpty
    override val classpathWithSources get() = lhs.classpathWithSources + rhs.classpathWithSources
    override fun resolve(): ResolvedClassPath {
        val left = lhs.resolve()
        val right = rhs.resolve()
        return ResolvedClassPath(left.classpath + right.classpath, left.buildScriptClasspath + right.buildScriptClasspath)
    }
    override val currentBuildFileVersion: Long get() = max(lhs.currentBuildFileVersion, rhs.currentBuildFileVersion)
}

internal class FirstNonEmptyClassPathResolver(val lhs: ClassPathResolver, val rhs: ClassPathResolver) : ClassPathResolver {
    override val resolverType: String get() = "(${lhs.resolverType} or ${rhs.resolverType})"
    override val classpath get() = lhs.classpath.takeIf { it.isNotEmpty() } ?: rhs.classpath
    override val classpathOrEmpty get() = lhs.classpathOrEmpty.takeIf { it.isNotEmpty() } ?: rhs.classpathOrEmpty
    override val buildScriptClasspath get() = lhs.buildScriptClasspath.takeIf { it.isNotEmpty() } ?: rhs.buildScriptClasspath
    override val buildScriptClasspathOrEmpty get() = lhs.buildScriptClasspathOrEmpty.takeIf { it.isNotEmpty() } ?: rhs.buildScriptClasspathOrEmpty
    override val classpathWithSources get() = lhs.classpathWithSources.takeIf {
        it.isNotEmpty()
    } ?: rhs.classpathWithSources
    override fun resolve(): ResolvedClassPath {
        val left = lhs.resolve()
        return if (left.classpath.isNotEmpty()) left else rhs.resolve()
    }
    override val currentBuildFileVersion: Long get() = max(lhs.currentBuildFileVersion, rhs.currentBuildFileVersion)
}

internal fun Path.contentFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(this))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}

/** Resolves independent project models concurrently, then combines their coherent snapshots. */
internal class ParallelClassPathResolver(
    private val resolvers: Collection<ClassPathResolver>
) : ClassPathResolver {
    override val resolverType: String = resolvers.joinToString(prefix = "parallel(", postfix = ")") { it.resolverType }
    override val classpath: Set<ClassPathEntry> get() = resolve().classpath
    override val buildScriptClasspath: Set<Path> get() = resolve().buildScriptClasspath

    override fun resolve(): ResolvedClassPath {
        val futures = resolvers.map { resolver ->
            java.util.concurrent.CompletableFuture.supplyAsync(resolver::resolve)
        }
        val models = futures.map { it.join() }
        return ResolvedClassPath(
            models.flatMap { it.classpath }.toSet(),
            models.flatMap { it.buildScriptClasspath }.toSet()
        )
    }
}
