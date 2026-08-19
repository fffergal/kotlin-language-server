package org.javacs.kt.classpath

import org.javacs.kt.LOG
import org.jetbrains.exposed.sql.Database
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.FileSystems

fun defaultClassPathResolver(workspaceRoots: Collection<Path>, db: Database? = null): ClassPathResolver {
    val roots = workspaceRoots.map { it.toAbsolutePath().normalize() }
        .distinct()
        .filter { candidate -> workspaceRoots.none { other ->
            val normalizedOther = other.toAbsolutePath().normalize()
            normalizedOther != candidate && candidate.startsWith(normalizedOther)
        } }
    ClassPathInstrumentation.resolverCreated(roots)

    fun cached(resolver: ClassPathResolver): ClassPathResolver =
        if (db != null && resolver.cacheIdentity != null) CachedClassPathResolver(resolver, db) else resolver

    val projectResolvers = roots.asSequence()
        .flatMap(::workspaceResolvers)
        .distinctBy { it.cacheIdentity ?: "${it.resolverType}:${System.identityHashCode(it)}" }
        .map(::cached)
        .toList()
    val projectModel = if (db != null && projectResolvers.size > 1) {
        ParallelClassPathResolver(projectResolvers)
    } else {
        projectResolvers.joined
    }
    val global = cached(ShellClassPathResolver.global(roots.firstOrNull()))
    return WithStdlibResolver(global.or(projectModel)).or(BackupClassPathResolver)
}

/** Searches the workspace for all files that could provide classpath info. */
internal fun workspaceResolvers(workspaceRoot: Path): Sequence<ClassPathResolver> {
    val ignored: List<PathMatcher> = ignoredPathPatterns(workspaceRoot, workspaceRoot.resolve(".gitignore"))
    return folderResolvers(workspaceRoot, ignored).asSequence()
}

/** Finds each distinct build once, even when a workspace contains Gradle subprojects. */
private fun folderResolvers(root: Path, ignored: List<PathMatcher>): Collection<ClassPathResolver> {
    val files = root.toFile()
        .walk()
        .onEnter { file -> ignored.none { it.matches(file.toPath()) } }
        .map { it.toPath() }
        .toList()

    val gradleBuildFiles = files.filter {
        it.endsWith("build.gradle") || it.endsWith("build.gradle.kts")
    }
    val gradleResolvers = gradleBuildFiles
        .groupBy { gradleBuildRoot(it.parent, root) }
        .mapNotNull { (projectRoot, buildFiles) ->
            val representative = sequenceOf(
                projectRoot.resolve("build.gradle.kts"),
                projectRoot.resolve("build.gradle")
            ).firstOrNull { it.toFile().isFile } ?: buildFiles.firstOrNull()
            representative?.let(GradleClassPathResolver::maybeCreate)
        }

    val otherResolvers = files.asSequence()
        .filterNot { it.endsWith("build.gradle") || it.endsWith("build.gradle.kts") }
        .mapNotNull {
            MavenClassPathResolver.maybeCreate(it)
                ?: ShellClassPathResolver.maybeCreate(it)
        }
        .toList()
    return gradleResolvers + otherResolvers
}

private fun gradleBuildRoot(buildDirectory: Path, workspaceRoot: Path): Path {
    var current: Path? = buildDirectory
    var nearestSettingsRoot: Path? = null
    while (current != null && current.startsWith(workspaceRoot)) {
        if (current.resolve("settings.gradle").toFile().isFile ||
            current.resolve("settings.gradle.kts").toFile().isFile) {
            nearestSettingsRoot = current
            break
        }
        current = current.parent
    }
    return nearestSettingsRoot ?: buildDirectory
}

/** Tries to read glob patterns from a gitignore and always applies generated-directory exclusions. */
private fun ignoredPathPatterns(root: Path, gitignore: Path): List<PathMatcher> {
    val configured = gitignore.toFile()
        .takeIf { it.exists() }
        ?.readLines()
        .orEmpty()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.removeSuffix("/") }
    val defaults = listOf(".git", ".gradle", "build", "out", "target", "node_modules")

    return (configured + defaults).distinct().mapNotNull { pattern ->
        try {
            LOG.debug("Adding ignore pattern '{}' from {}", pattern, gitignore)
            FileSystems.getDefault().getPathMatcher("glob:$root**/$pattern")
        } catch (e: Exception) {
            LOG.warn("Did not recognize gitignore pattern: '{}' ({})", pattern, e.message)
            null
        }
    }
}
