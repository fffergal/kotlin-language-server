package org.javacs.kt.classpath

import org.javacs.kt.LOG
import org.javacs.kt.util.execAndReadStdoutAndStderr
import org.javacs.kt.util.KotlinLSException
import org.javacs.kt.util.isOSWindows
import org.javacs.kt.util.findCommandOnPath
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal class GradleClassPathResolver(private val path: Path, private val includeKotlinDSL: Boolean): ClassPathResolver {
    override val resolverType: String = "Gradle"
    private val projectDirectory: Path get() = path.parent
    override val cacheIdentity: String get() = "gradle:${projectDirectory.toAbsolutePath().normalize()}"
    override val freshnessFingerprint: String get() = "gradle-model-with-sources-v1|" + projectDirectory.toFile().walkTopDown()
        .onEnter { it.name !in setOf(".git", ".gradle", "build", "out", "target", "node_modules") }
        .filter { file -> file.isFile && file.name.let { name ->
            name == "build.gradle" || name == "build.gradle.kts" ||
                name == "settings.gradle" || name == "settings.gradle.kts" ||
                name == "gradle.properties" || name == "gradle-wrapper.properties" ||
                name == "gradle.lockfile" || name.endsWith(".versions.toml") ||
                (name.endsWith(".lockfile") && "dependency-locks" in file.toPath().toString())
        } }
        .map { file -> "${file.toPath().toAbsolutePath().normalize()}:${file.toPath().contentFingerprint()}" }
        .sorted()
        .joinToString("|")

    override val classpath: Set<ClassPathEntry> get() {
        val scripts = listOf("projectClassPathFinder.gradle")
        val tasks = listOf("kotlinLSPProjectDeps")

        return readDependenciesViaGradleCLI(projectDirectory, scripts, tasks)
            .apply { if (isNotEmpty()) LOG.info("Successfully resolved dependencies for '${projectDirectory.fileName}' using Gradle") }
            .map { ClassPathEntry(it, null) }.toSet()
    }
    override val buildScriptClasspath: Set<Path> get() {
        return if (includeKotlinDSL) {
            val scripts = listOf("kotlinDSLClassPathFinder.gradle")
            val tasks = listOf("kotlinLSPKotlinDSLDeps")

            return readDependenciesViaGradleCLI(projectDirectory, scripts, tasks)
                .apply { if (isNotEmpty()) LOG.info("Successfully resolved build script dependencies for '${projectDirectory.fileName}' using Gradle") }
        } else {
            emptySet()
        }
    }

    override val currentBuildFileVersion: Long get() = path.toFile().lastModified()

    override fun resolve(): ResolvedClassPath {
        val scripts = mutableListOf("projectClassPathFinder.gradle")
        val tasks = mutableListOf("kotlinLSPProjectDeps")
        if (includeKotlinDSL) {
            scripts += "kotlinDSLClassPathFinder.gradle"
            tasks += "kotlinLSPKotlinDSLDeps"
        }
        val (classpath, buildScripts) = readCoherentGradleModel(projectDirectory, scripts, tasks)
        if (classpath.isNotEmpty()) {
            LOG.info("Successfully resolved coherent project model for '{}' using Gradle", projectDirectory.fileName)
        }
        return ResolvedClassPath(classpath, buildScripts)
    }

    companion object {
        /** Create a Gradle resolver if a file is a build script. */
        fun maybeCreate(file: Path): GradleClassPathResolver? =
            file.takeIf { file.endsWith("build.gradle") || file.endsWith("build.gradle.kts") }
                ?.let { GradleClassPathResolver(it, includeKotlinDSL = file.toString().endsWith(".kts")) }
    }
}

private fun readCoherentGradleModel(
    projectDirectory: Path,
    gradleScripts: List<String>,
    gradleTasks: List<String>
): Pair<Set<ClassPathEntry>, Set<Path>> {
    LOG.info("Resolving coherent project model for '{}' through one Gradle invocation...", projectDirectory.fileName)
    val tmpScripts = gradleScripts.map { gradleScriptToTempFile(it).toPath().toAbsolutePath() }
    return try {
        val gradle = getGradleCommand(projectDirectory)
        val command = listOf(gradle.toString()) +
            tmpScripts.flatMap { listOf("-I", it.toString()) } + gradleTasks + listOf("--console=plain")
        val (output, errors) = execAndReadStdoutAndStderr(command, projectDirectory)
        if ("FAILURE: Build failed" in errors) throw KotlinLSException("Gradle project-model task failed: $errors")
        Pair(parseGradleClassPath(output), parseGradlePaths(output, "kotlin-lsp-buildscript"))
    } finally {
        tmpScripts.forEach(Files::deleteIfExists)
    }
}

private val gradleEntryPattern = Regex("(?m)^kotlin-lsp-gradle-entry\\t([^\\t\\r\\n]+)\\t([^\\r\\n]*)$")

private fun parseGradleClassPath(output: String): Set<ClassPathEntry> {
    val attached = gradleEntryPattern.findAll(output).associate { match ->
        val compiled = Paths.get(match.groupValues[1])
        val source = match.groupValues[2].takeIf(String::isNotEmpty)?.let(Paths::get)
        compiled to ClassPathEntry(compiled, source)
    }
    val legacy = parseGradlePaths(output, "kotlin-lsp-gradle")
        .associateWith { compiled -> ClassPathEntry(compiled, null) }
    return (legacy + attached).values.toSet()
}

private fun parseGradlePaths(output: String, prefix: String): Set<Path> =
    Regex("${Regex.escape(prefix)} (.+)(?:\\r?\\n)")
        .findAll(output)
        .mapNotNull { it.groups[1]?.value?.let(Paths::get) }
        .filter { it.toString().lowercase().endsWith(".jar") || Files.isDirectory(it) }
        .toSet()

private fun gradleScriptToTempFile(scriptName: String, deleteOnExit: Boolean = false): File {
    val config = File.createTempFile("classpath", ".gradle")
    if (deleteOnExit) {
        config.deleteOnExit()
    }

    LOG.debug("Creating temporary gradle file {}", config.absolutePath)

    config.bufferedWriter().use { configWriter ->
        GradleClassPathResolver::class.java.getResourceAsStream("/$scriptName").bufferedReader().use { configReader ->
            configReader.copyTo(configWriter)
        }
    }

    return config
}

private fun getGradleCommand(workspace: Path): Path {
    val wrapperName = if (isOSWindows()) "gradlew.bat" else "gradlew"
    val wrapper = workspace.resolve(wrapperName).toAbsolutePath()
    if (Files.isExecutable(wrapper)) {
        return wrapper
    } else {
        return workspace.parent?.let(::getGradleCommand)
            ?: findCommandOnPath("gradle")
            ?: throw KotlinLSException("Could not find 'gradle' on PATH")
    }
}

private fun readDependenciesViaGradleCLI(projectDirectory: Path, gradleScripts: List<String>, gradleTasks: List<String>): Set<Path> {
    LOG.info("Resolving dependencies for '{}' through Gradle's CLI using tasks {}...", projectDirectory.fileName, gradleTasks)

    val tmpScripts = gradleScripts.map { gradleScriptToTempFile(it, deleteOnExit = false).toPath().toAbsolutePath() }
    val gradle = getGradleCommand(projectDirectory)

    val command = listOf(gradle.toString()) + tmpScripts.flatMap { listOf("-I", it.toString()) } + gradleTasks + listOf("--console=plain")
    val dependencies = findGradleCLIDependencies(command, projectDirectory)
        ?.also { LOG.debug("Classpath for task {}", it) }
        .orEmpty()
        .filter { it.toString().lowercase().endsWith(".jar") || Files.isDirectory(it) } // Some Gradle plugins seem to cause this to output POMs, therefore filter JARs
        .toSet()

    tmpScripts.forEach(Files::delete)
    return dependencies
}

private fun findGradleCLIDependencies(command: List<String>, projectDirectory: Path): Set<Path>? {
    val (result, errors) = execAndReadStdoutAndStderr(command, projectDirectory)
    if ("FAILURE: Build failed" in errors) {
        LOG.warn("Gradle task failed: {}", errors)
    } else {
        for (error in errors.lines()) {
            if ("ERROR: " in error) {
                LOG.warn("Gradle error: {}", error)
            }
        }
    }
    return parseGradleCLIDependencies(result)
}

private val artifactPattern by lazy { "kotlin-lsp-gradle (.+)(?:\r?\n)".toRegex() }
private val gradleErrorWherePattern by lazy { "\\*\\s+Where:[\r\n]+(\\S\\.*)".toRegex() }

private fun parseGradleCLIDependencies(output: String): Set<Path>? {
    LOG.debug(output)
    val artifacts = artifactPattern.findAll(output)
        .mapNotNull { Paths.get(it.groups[1]?.value) }
        .filterNotNull()
    return artifacts.toSet()
}
