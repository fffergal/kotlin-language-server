package org.javacs.kt

import org.javacs.kt.compiler.CompilationKind
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.fileExtension
import org.javacs.kt.util.filePath
import org.javacs.kt.util.describeURI
import org.javacs.kt.index.SymbolIndex
import org.javacs.kt.progress.Progress
import com.intellij.lang.Language
import org.javacs.kt.database.DatabaseService
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.CompositeBindingContext
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import kotlin.concurrent.withLock
import java.nio.file.Path
import java.nio.file.Paths
import java.net.URI
import java.util.concurrent.locks.ReentrantLock
import org.jetbrains.kotlin.resolve.lazy.NoDescriptorForDeclarationException

internal enum class SemanticSnapshotState {
    ABSENT,
    CURRENT,
    STALE,
}

internal data class WorkspaceCompilation(
    val requestedFile: CompiledFile,
    val sourceFiles: Collection<KtFile>,
    val context: BindingContext,
)

class SourcePath(
    private val cp: CompilerClassPath,
    private val contentProvider: URIContentProvider,
    private val indexingConfig: IndexingConfiguration,
    private val databaseService: DatabaseService
) {
    private val files = mutableMapOf<URI, SourceFile>()
    private val parseDataWriteLock = ReentrantLock()
    private val compileLock = ReentrantLock()

    private val indexAsync = AsyncExecutor()
    var indexEnabled: Boolean by indexingConfig::enabled
    val index = SymbolIndex(databaseService)

    var beforeCompileCallback: () -> Unit = {}

    var progressFactory: Progress.Factory = Progress.Factory.None
        set(factory: Progress.Factory) {
            field = factory
            index.progressFactory = factory
        }

    @Suppress("LongParameterList")
    private inner class SourceFile(
        val uri: URI,
        var content: String,
        val path: Path? = uri.filePath,
        var parsed: KtFile? = null,
        var compiledFile: KtFile? = null,
        var compiledContext: BindingContext? = null,
        var module: ModuleDescriptor? = null,
        var compiledSourcePath: Collection<KtFile>? = null,
        val language: Language? = null,
        val isTemporary: Boolean = false, // A temporary source file will not be returned by .all()
        var lastSavedFile: KtFile? = null,
        var dependenciesStale: Boolean = false,
    ) {
        val extension: String? = uri.fileExtension ?: "kt" // TODO: Use language?.associatedFileType?.defaultExtension again
        val isScript: Boolean = extension == "kts"
        val kind: CompilationKind =
            if (path?.fileName?.toString()?.endsWith(".gradle.kts") ?: false) CompilationKind.BUILD_SCRIPT
            else CompilationKind.DEFAULT
        val snapshotState: SemanticSnapshotState
            get() = when {
                compiledFile == null || compiledContext == null || module == null || compiledSourcePath == null ->
                    SemanticSnapshotState.ABSENT
                compiledFile?.text != content || dependenciesStale -> SemanticSnapshotState.STALE
                else -> SemanticSnapshotState.CURRENT
            }

        fun put(newContent: String) {
            content = newContent
        }

        fun markDependenciesStale() {
            if (snapshotState != SemanticSnapshotState.ABSENT) dependenciesStale = true
        }

        fun clearCompilation() {
            compiledFile = null
            compiledContext = null
            module = null
            compiledSourcePath = null
            dependenciesStale = false
        }

        fun clean() {
            parsed = null
            clearCompilation()
        }

        fun parse() {
            // TODO: Create PsiFile using the stored language instead
            parsed = cp.compiler.createKtFile(content, path ?: Paths.get("sourceFile.virtual.$extension"), kind)
        }

        fun parseIfChanged() {
            if (content != parsed?.text) {
                parse()
            }
        }

        fun compileIfAbsent() = parseIfChanged().apply {
            if (snapshotState == SemanticSnapshotState.ABSENT) doCompile()
        }

        fun compileIfStale() = parseIfChanged().apply {
            if (snapshotState != SemanticSnapshotState.CURRENT) doCompile()
        }

        fun compile() = parse().apply { doCompile() }

        private fun doCompile() {
            LOG.debug("Compiling {} with workspace sources", path?.fileName)
            if (java.lang.Boolean.getBoolean("kotlinLanguageServer.tracePerformance")) {
                LOG.info("Performance trace: semantic-compilation {}", path?.toAbsolutePath())
            }

            val oldFile = clone()
            val currentFile = parsed!!
            beforeCompileCallback.invoke()
            val relevant = allIncludingThis()
            val (result, workspaceSources) = try {
                cp.compiler.compileKtFile(currentFile, relevant, kind) to relevant
            } catch (error: NoDescriptorForDeclarationException) {
                val complete = all()
                if (complete.size <= relevant.size) throw error
                cp.compiler.compileKtFile(currentFile, complete, kind) to complete
            }
            val (context, module) = result
            val accepted = parseDataWriteLock.withLock {
                if (parsed === currentFile && content == currentFile.text) {
                    compiledContext = context
                    this.module = module
                    compiledFile = currentFile
                    compiledSourcePath = workspaceSources
                    dependenciesStale = false
                    true
                } else {
                    false
                }
            }

            if (accepted) {
                refreshWorkspaceIndexes(listOfNotNull(oldFile), listOf(clone()))
            }
        }

        fun prepareCompiledFile(): CompiledFile =
            parseIfChanged().apply { compileIfAbsent() }.let { doPrepareCompiledFile() }

        private fun doPrepareCompiledFile(): CompiledFile =
            CompiledFile(content, compiledFile!!, compiledContext!!, module!!, compiledSourcePath!!, cp, isScript, kind)

        private fun allIncludingThis(): Collection<KtFile> = parseIfChanged().let {
            if (isTemporary) (all().asSequence() + sequenceOf(parsed!!)).toList()
            else relevantSources(this)
        }

        // Creates a shallow copy
        fun clone(): SourceFile = SourceFile(
            uri, content, path, parsed, compiledFile, compiledContext, module, compiledSourcePath,
            language, isTemporary, lastSavedFile, dependenciesStale
        )
    }


    private fun relevantSourceFiles(source: SourceFile): List<SourceFile> {
        val current = source.apply { parseIfChanged() }.parsed!!
        val currentPackage = current.packageFqName.asString()
        val imports = current.importDirectives.mapNotNull { it.importPath }
        return files.values.filter { candidate ->
            val parsed = candidate.apply { parseIfChanged() }.parsed!!
            candidate === source || parsed.packageFqName.asString() == currentPackage ||
                parsed.declarations.mapNotNull { it.name }.any { name ->
                    val packageName = parsed.packageFqName.asString()
                    val declaration = if (packageName.isEmpty()) name else "$packageName.$name"
                    imports.any { imported ->
                        val importedName = imported.fqName.asString()
                        importedName == declaration ||
                            importedName.startsWith("$declaration.") ||
                            (imported.isAllUnder && declaration.startsWith("$importedName."))
                    }
                }
        }
    }

    private fun relevantSources(source: SourceFile): Collection<KtFile> =
        relevantSourceFiles(source).map { it.parsed!! }

    private fun markDependentsStale(changed: Set<SourceFile>) {
        val pending = ArrayDeque(changed)
        val visited = changed.toMutableSet()
        while (pending.isNotEmpty()) {
            val dependency = pending.removeFirst()
            files.values.asSequence()
                .filterNot { it in visited }
                .filter { candidate -> dependency in relevantSourceFiles(candidate) }
                .forEach { candidate ->
                    parseDataWriteLock.withLock { candidate.markDependenciesStale() }
                    visited.add(candidate)
                    pending.add(candidate)
                }
        }
    }

    private fun sourceFile(uri: URI): SourceFile {
        if (uri !in files) {
            // Fallback solution, usually *all* source files
            // should be added/opened through SourceFiles
            LOG.warn("Requested source file {} is not on source path, this is most likely a bug. Adding it now temporarily...", describeURI(uri))
            put(uri, contentProvider.contentOf(uri), null, temporary = true)
        }
        return files[uri]!!
    }

    fun put(uri: URI, content: String, language: Language?, temporary: Boolean = false) {
        assert(!content.contains('\r'))

        if (temporary) {
            LOG.info("Adding temporary source file {} to source path", describeURI(uri))
        }

        if (uri in files) {
            val source = sourceFile(uri)
            if (source.content != content) {
                markDependentsStale(setOf(source))
                source.put(content)
                markDependentsStale(setOf(source))
            }
        } else {
            val source = SourceFile(uri, content, language = language, isTemporary = temporary)
            files[uri] = source
            if (files.values.any { it !== source && it.snapshotState != SemanticSnapshotState.ABSENT }) {
                markDependentsStale(setOf(source))
            }
        }
    }

    fun deleteIfTemporary(uri: URI): Boolean =
        if (sourceFile(uri).isTemporary) {
            LOG.info("Removing temporary source file {} from source path", describeURI(uri))
            delete(uri)
            true
        } else {
            false
        }

    fun delete(uri: URI) {
        files[uri]?.let {
            markDependentsStale(setOf(it))
            refreshWorkspaceIndexes(listOf(it), listOf())
            cp.compiler.removeGeneratedCode(listOfNotNull(it.lastSavedFile))
        }

        files.remove(uri)
    }

    /**
     * Get the latest content of a file
     */
    fun content(uri: URI): String = sourceFile(uri).content

    fun parsedFile(uri: URI): KtFile = sourceFile(uri).apply { parseIfChanged() }.parsed!!

    internal fun snapshotState(uri: URI): SemanticSnapshotState = compileLock.withLock {
        sourceFile(uri).snapshotState
    }

    /** Compile the latest document and dependency version. */
    fun currentVersion(uri: URI): CompiledFile = compileLock.withLock {
        sourceFile(uri).apply { compileIfStale() }.prepareCompiledFile()
    }

    /** Return current semantic results for every workspace source and the requested file. */
    internal fun currentWorkspaceVersion(uri: URI): WorkspaceCompilation = compileLock.withLock {
        val workspaceUris = files.values.filterNot { it.isTemporary }.map { it.uri }
        val context = compileFiles(workspaceUris)
        val requested = sourceFile(uri).apply { compileIfStale() }.prepareCompiledFile()
        WorkspaceCompilation(requested, all(), context)
    }

    /** Return the newest available snapshot, including a stale one. */
    fun latestCompiledVersion(uri: URI): CompiledFile = compileLock.withLock {
        sourceFile(uri).prepareCompiledFile()
    }

    /**
     * Compile changed files
     */
    fun compileFiles(all: Collection<URI>): BindingContext = compileLock.withLock {
        // Refresh requested files and any existing snapshots made stale by them.
        val requested = all.map { files[it]!! }
        val staleDependents = files.values.filter { it.snapshotState == SemanticSnapshotState.STALE }
        val sources = (requested + staleDependents).distinct()
        val allChanged = sources.filter { it.snapshotState != SemanticSnapshotState.CURRENT }
        val unchangedContexts = (requested - allChanged.toSet()).mapNotNull { it.compiledContext }
        val (changedBuildScripts, changedSources) = allChanged.partition { it.kind == CompilationKind.BUILD_SCRIPT }

        // Compile changed files
        fun compileAndUpdate(changed: List<SourceFile>, kind: CompilationKind): BindingContext? {
            if (changed.isEmpty()) return null

            // Get clones of the old files, so we can remove the old declarations from the index
            val oldFiles = changed.mapNotNull {
                if (it.compiledFile?.text != it.content || it.parsed?.text != it.content) {
                    it.clone()
                } else {
                    null
                }
            }

            // Parse the files that have changed
            val parse = changed.associateWith { it.apply { parseIfChanged() }.parsed!! }

            // Get all the files. This will parse them if they changed
            val allFiles = all()
            beforeCompileCallback.invoke()
            val (context, module) = cp.compiler.compileKtFiles(parse.values, allFiles, kind)

            // Install only results whose exact parsed snapshot is still current.
            val accepted = parse.mapNotNull { (file, parsed) ->
                parseDataWriteLock.withLock {
                    if (file.parsed === parsed && file.content == parsed.text) {
                        file.compiledFile = parsed
                        file.compiledContext = context
                        file.module = module
                        file.compiledSourcePath = allFiles
                        file.dependenciesStale = false
                        file
                    } else {
                        null
                    }
                }
            }

            // Only index normal files, not build files.
            if (kind == CompilationKind.DEFAULT && accepted.isNotEmpty()) {
                val acceptedOldFiles = oldFiles.filter { old -> accepted.any { it.uri == old.uri } }
                refreshWorkspaceIndexes(acceptedOldFiles, accepted.map { it.clone() })
            }

            return context
        }

        val buildScriptsContext = compileAndUpdate(changedBuildScripts, CompilationKind.BUILD_SCRIPT)
        val sourcesContext = compileAndUpdate(changedSources, CompilationKind.DEFAULT)

        // Combine with past compilations
        val combined = listOf(buildScriptsContext, sourcesContext).filterNotNull() + unchangedContexts

        return CompositeBindingContext.create(combined)
    }

    fun compileAllFiles() {
        // TODO: Investigate the possibility of compiling all files at once, instead of iterating here
        // At the moment, compiling all files at once sometimes leads to an internal error from the TopDownAnalyzer
        files.keys.forEach {
            // If one of the files fails to compile, we compile the others anyway
            try {
                compileFiles(listOf(it))
            } catch (ex: Exception) {
                LOG.printStackTrace(ex)
            }
        }
    }

    /**
     * Saves a file. This generates code for the file and deletes previously generated code for this file.
     */
    fun save(uri: URI) {
        files[uri]?.let {
            if (!it.isScript) {
                // If the code generation fails for some reason, we generate code for the other files anyway
                try {
                    cp.compiler.removeGeneratedCode(listOfNotNull(it.lastSavedFile))
                    it.module?.let { module ->
                        it.compiledContext?.let { context ->
                            cp.compiler.generateCode(module, context, listOfNotNull(it.compiledFile))
                            it.lastSavedFile = it.compiledFile
                        }
                    }
                } catch (ex: Exception) {
                    LOG.printStackTrace(ex)
                }
            }
        }
    }

    fun saveAllFiles() {
        files.keys.forEach { save(it) }
    }

    fun refreshDependencyIndexes() {
        compileAllFiles()

        val module = files.values.first { it.module != null }.module
        if (module != null) {
            refreshDependencyIndexes(module)
        }
    }

    /**
     * Refreshes the indexes. If already done, refreshes only the declarations in the files that were changed.
     */
    private fun refreshWorkspaceIndexes(oldFiles: List<SourceFile>, newFiles: List<SourceFile>) = indexAsync.execute {
        if (indexEnabled) {
            val oldDeclarations = getDeclarationDescriptors(oldFiles)
            val newDeclarations = getDeclarationDescriptors(newFiles)

            // Index the new declarations in the Kotlin source files that were just compiled, removing the old ones
            index.updateIndexes(oldDeclarations, newDeclarations)
        }
    }

    /**
     * Refreshes the indexes. If already done, refreshes only the declarations in the files that were changed.
     */
    private fun refreshDependencyIndexes(module: ModuleDescriptor) = indexAsync.execute {
        if (indexEnabled) {
            val declarations = getDeclarationDescriptors(files.values)
            index.refresh(module, declarations)
        }
    }

    // Gets all the declaration descriptors for the collection of files
    private fun getDeclarationDescriptors(files: Collection<SourceFile>) =
        files.flatMap { file ->
            val compiledFile = file.compiledFile ?: file.parsed
            val module = file.module
            if (compiledFile != null && module != null) {
                module.getPackage(compiledFile.packageFqName).memberScope.getContributedDescriptors(
                    DescriptorKindFilter.ALL
                ) { name -> compiledFile.declarations.map { it.name }.contains(name.toString()) }
            } else {
                listOf()
            }
        }.asSequence()

    /**
     * Recompiles all source files that are initialized.
     */
    fun refresh() {
        val initialized = files.values.any { it.parsed != null }
        if (initialized) {
            LOG.info("Refreshing source path")
            files.values.forEach { it.clean() }
            files.values.forEach { it.compile() }
        }
    }

    /**
     * Get parsed trees for all .kt files on source path
     */
    fun all(includeHidden: Boolean = false): Collection<KtFile> =
            files.values
                .filter { includeHidden || !it.isTemporary }
                .map { it.apply { parseIfChanged() }.parsed!! }
}
