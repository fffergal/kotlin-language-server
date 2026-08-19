package org.javacs.kt

import org.javacs.kt.classpath.ClassPathEntry
import org.javacs.kt.classpath.ClassPathInstrumentation
import org.javacs.kt.classpath.defaultClassPathResolver
import org.javacs.kt.compiler.Compiler
import org.javacs.kt.database.DatabaseService
import org.javacs.kt.util.AsyncExecutor
import java.io.Closeable
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * Manages the class path (compiled JARs, etc), the Java source path
 * and the compiler. Note that Kotlin sources are stored in SourcePath.
 */
class CompilerClassPath(
    private val config: CompilerConfiguration,
    private val scriptsConfig: ScriptsConfiguration,
    private val codegenConfig: CodegenConfiguration,
    private val databaseService: DatabaseService,
    private val resolverFactory: (Collection<Path>, org.jetbrains.exposed.sql.Database?) -> org.javacs.kt.classpath.ClassPathResolver = ::defaultClassPathResolver
) : Closeable {
    val workspaceRoots = mutableSetOf<Path>()
    private val modelLock = Any()
    @Volatile private var modelReady = CompletableFuture<Boolean>()
    private var inFlightRefresh: CompletableFuture<Boolean>? = null
    private var inFlightReadiness: CompletableFuture<Boolean>? = null

    private val javaSourcePath = mutableSetOf<Path>()
    private val buildScriptClassPath = mutableSetOf<Path>()
    val classPath = mutableSetOf<ClassPathEntry>()
    val outputDirectory: File = Files.createTempDirectory("klsBuildOutput").toFile()
    val javaHome: String? = System.getProperty("java.home", null)

    private var compilerInstance: Compiler? = null
    private var compilerWarmup: CompletableFuture<Compiler>? = null
    private var warmupJavaSources = emptySet<Path>()
    private var warmupCompiledPaths = emptySet<Path>()
    private var warmupBuildScriptPaths = emptySet<Path>()
    private var installedJavaSources = emptySet<Path>()
    private var installedCompiledPaths = emptySet<Path>()
    private var installedBuildScriptPaths = emptySet<Path>()
    val compiler: Compiler
        get() = synchronized(modelLock) {
            compilerInstance ?: compilerWarmup?.let(::claimCompiler)
                ?: createCompiler().also {
                    installCompiler(
                        it,
                        javaSourcePath.toSet(),
                        classPath.map { entry -> entry.compiledJar }.toSet(),
                        buildScriptClassPath.toSet()
                    )
                }
        }

    private fun createCompiler(
        javaSources: Set<Path> = javaSourcePath.toSet(),
        compiledPaths: Set<Path> = classPath.map { it.compiledJar }.toSet(),
        buildScriptPaths: Set<Path> = buildScriptClassPath.toSet()
    ): Compiler = Compiler(
        javaSources,
        compiledPaths,
        buildScriptPaths,
        scriptsConfig,
        codegenConfig,
        outputDirectory
    ).also { it.updateConfiguration(config) }

    private fun startCompilerWarmup(
        javaSources: Set<Path> = emptySet(),
        compiledPaths: Set<Path> = emptySet(),
        buildScriptPaths: Set<Path> = emptySet()
    ): CompletableFuture<Compiler> = synchronized(modelLock) {
        compilerWarmup ?: run {
            warmupJavaSources = javaSources
            warmupCompiledPaths = compiledPaths
            warmupBuildScriptPaths = buildScriptPaths
            CompletableFuture.supplyAsync {
                createCompiler(javaSources, compiledPaths, buildScriptPaths)
            }.also { compilerWarmup = it }
        }
    }

    private fun claimCompiler(warmup: CompletableFuture<Compiler>): Compiler = warmup.join().also {
        if (compilerWarmup === warmup) compilerWarmup = null
        installCompiler(it, warmupJavaSources, warmupCompiledPaths, warmupBuildScriptPaths)
    }

    private fun installCompiler(
        compiler: Compiler,
        javaSources: Set<Path>,
        compiledPaths: Set<Path>,
        buildScriptPaths: Set<Path>
    ) {
        compilerInstance = compiler
        installedJavaSources = javaSources
        installedCompiledPaths = compiledPaths
        installedBuildScriptPaths = buildScriptPaths
    }

    private fun discardCompilerWarmup(warmup: CompletableFuture<Compiler>?) {
        if (warmup == null) return
        synchronized(modelLock) {
            if (compilerWarmup === warmup) compilerWarmup = null
        }
        warmup.thenAccept(Compiler::close)
    }

    private val async = AsyncExecutor()

    /** Updates and possibly reinstantiates the compiler using new paths. */
    @Suppress("TooGenericExceptionCaught", "ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    private fun refresh(
        updateClassPath: Boolean = true,
        updateBuildScriptClassPath: Boolean = true,
        updateJavaSourcePath: Boolean = true
    ): Boolean {
        val resolvedJavaSources = javaSourcePath.toSet()
        val compilerWarmupForRefresh = if (compilerInstance == null) {
            startCompilerWarmup(
                javaSourcePath.toSet(),
                classPath.map { it.compiledJar }.toSet(),
                buildScriptClassPath.toSet()
            )
        } else null
        val resolver = resolverFactory(workspaceRoots, databaseService.db)
        val resolved = if (updateClassPath || updateBuildScriptClassPath) {
            ClassPathInstrumentation.classpathAccess()
            ClassPathInstrumentation.classpathWithSourcesAccess()
            ClassPathInstrumentation.buildScriptClasspathAccess()
            try {
                resolver.resolve()
            } catch (error: Exception) {
                discardCompilerWarmup(compilerWarmupForRefresh)
                LOG.warn("Project-model refresh failed; retaining the last valid model: {}", error.message)
                return false
            }
        } else null

        synchronized(modelLock) {
            if (resolved != null && resolved.classpath.isEmpty() && classPath.isNotEmpty()) {
                discardCompilerWarmup(compilerWarmupForRefresh)
                LOG.warn("Project-model refresh returned an empty classpath; retaining the last valid model")
                return false
            }

            var refreshCompiler = updateJavaSourcePath && resolvedJavaSources != installedJavaSources
            if (refreshCompiler) {
                logAdded(resolvedJavaSources - installedJavaSources, "Java source path")
                logRemoved(installedJavaSources - resolvedJavaSources, "Java source path")
            }
            if (updateClassPath && resolved != null && resolved.classpath != classPath) {
                syncPaths(classPath, resolved.classpath, "class path with sources") { it.compiledJar }
                refreshCompiler = true
            }
            if (updateBuildScriptClassPath && resolved != null && resolved.buildScriptClasspath != buildScriptClassPath) {
                syncPaths(buildScriptClassPath, resolved.buildScriptClasspath, "build script class path") { it }
                refreshCompiler = true
            }

            if (refreshCompiler) {
                LOG.info("Reinstantiating compiler")
                val reusable = compilerInstance ?: compilerWarmupForRefresh?.let(::claimCompiler)
                val newJavaSources = javaSourcePath.toSet()
                val newCompiledPaths = classPath.map { it.compiledJar }.toSet()
                val newBuildScriptPaths = buildScriptClassPath.toSet()
                val additiveUpdate = installedJavaSources == newJavaSources &&
                    installedCompiledPaths.all(newCompiledPaths::contains) &&
                    installedBuildScriptPaths.all(newBuildScriptPaths::contains)
                if (reusable != null && additiveUpdate) {
                    reusable.updateClasspath(newJavaSources, newCompiledPaths, newBuildScriptPaths)
                    reusable.updateConfiguration(config)
                    installCompiler(reusable, newJavaSources, newCompiledPaths, newBuildScriptPaths)
                } else {
                    discardCompilerWarmup(compilerWarmupForRefresh)
                    reusable?.close()
                    compilerInstance?.takeUnless { it === reusable }?.close()
                    installCompiler(
                        createCompiler(newJavaSources, newCompiledPaths, newBuildScriptPaths),
                        newJavaSources,
                        newCompiledPaths,
                        newBuildScriptPaths
                    )
                }
            }
            return refreshCompiler
        }
    }

    /** Records roots without starting dependency discovery. */
    fun recordWorkspaceRoots(roots: Collection<Path>) {
        synchronized(modelLock) {
            workspaceRoots.clear()
            workspaceRoots.addAll(normalizeRoots(roots))
            if (compilerInstance == null) startCompilerWarmup(javaSources = javaSourcePath.toSet())
            if (modelReady.isDone || inFlightRefresh?.isDone == false) modelReady = CompletableFuture()
        }
    }

    /** Records Java sources found during the source-path traversal. */
    fun recordJavaSourcePaths(paths: Collection<Path>) {
        synchronized(modelLock) {
            javaSourcePath.clear()
            javaSourcePath.addAll(paths)
        }
    }

    /** Coalesces concurrent refresh requests into one model resolution. */
    fun refreshAsync(): CompletableFuture<Boolean> = synchronized(modelLock) {
        inFlightRefresh?.takeUnless { it.isDone }?.let { active ->
            return@synchronized if (modelReady === inFlightReadiness) active
            else active.thenCompose { refreshAsync() }
        }
        if (modelReady.isDone) modelReady = CompletableFuture()
        val readinessForRefresh = modelReady
        val future = async.compute { refresh() }
        inFlightRefresh = future
        inFlightReadiness = readinessForRefresh
        future.whenComplete { refreshed, error ->
            synchronized(modelLock) {
                if (inFlightRefresh === future) {
                    inFlightRefresh = null
                    inFlightReadiness = null
                }
                if (error == null) readinessForRefresh.complete(refreshed) else readinessForRefresh.completeExceptionally(error)
            }
        }
        future
    }

    /** Waits until the startup model applicable to this workspace is available. */
    fun awaitReady(file: Path? = null) {
        val ready = modelReady
        if (!ready.isDone) LOG.info("Waiting for project model{}", file?.let { " for $it" } ?: "")
        ready.join()
    }

    /** Synchronizes the given two path sets and logs the differences. */
    private fun <T> syncPaths(dest: MutableSet<T>, new: Set<T>, name: String, toPath: (T) -> Path) {
        val added = new - dest
        val removed = dest - new

        logAdded(added.map(toPath), name)
        logRemoved(removed.map(toPath), name)

        dest.removeAll(removed)
        dest.addAll(added)
    }

    fun updateCompilerConfiguration() {
        compilerInstance?.updateConfiguration(config)
    }

    fun addWorkspaceRoot(root: Path): Boolean {
        LOG.info("Searching for dependencies and Java sources in workspace root {}", root)

        workspaceRoots.add(root)
        javaSourcePath.addAll(findJavaSourceFiles(root))

        return refresh()
    }

    fun removeWorkspaceRoot(root: Path): Boolean {
        LOG.info("Removing dependencies and Java source path from workspace root {}", root)

        workspaceRoots.remove(root)
        javaSourcePath.removeAll(findJavaSourceFiles(root))

        return refresh()
    }

    fun createdOnDisk(file: Path): Boolean {
        if (isJavaSource(file)) {
            javaSourcePath.add(file)
        }
        return changedOnDisk(file)
    }

    fun deletedOnDisk(file: Path): Boolean {
        if (isJavaSource(file)) {
            javaSourcePath.remove(file)
        }
        return changedOnDisk(file)
    }

    fun changedOnDisk(file: Path): Boolean {
        val buildScript = isBuildScript(file)
        val javaSource = isJavaSource(file)
        if (buildScript || javaSource) {
            return refresh(updateClassPath = buildScript, updateBuildScriptClassPath = false, updateJavaSourcePath = javaSource)
        } else {
            return false
        }
    }

    private fun isJavaSource(file: Path): Boolean = file.fileName.toString().endsWith(".java")

    private fun isBuildScript(file: Path): Boolean = file.fileName.toString().let { it == "pom.xml" || it == "build.gradle" || it == "build.gradle.kts" }

    private fun findJavaSourceFiles(root: Path): Set<Path> {
        val sourceMatcher = FileSystems.getDefault().getPathMatcher("glob:*.java")
        return SourceExclusions(listOf(root), scriptsConfig)
            .walkIncluded()
            .filter { sourceMatcher.matches(it.fileName) }
            .toSet()
    }

    override fun close() {
        compilerInstance?.close()
        compilerWarmup?.thenAccept(Compiler::close)
        compilerWarmup = null
        outputDirectory.delete()
    }
}

private fun logAdded(sources: Collection<Path>, name: String) {
    when {
        sources.isEmpty() -> return
        sources.size > 5 -> LOG.info("Adding {} files to {}", sources.size, name)
        else -> LOG.info("Adding {} to {}", sources, name)
    }
}

private fun logRemoved(sources: Collection<Path>, name: String) {
    when {
        sources.isEmpty() -> return
        sources.size > 5 -> LOG.info("Removing {} files from {}", sources.size, name)
        else -> LOG.info("Removing {} from {}", sources, name)
    }
}
