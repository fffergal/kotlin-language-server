package org.javacs.kt

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import org.javacs.kt.codeaction.codeActions
import org.javacs.kt.completion.completions
import org.javacs.kt.definition.goToDefinition
import org.javacs.kt.diagnostic.convertDiagnostic
import org.javacs.kt.formatting.FormattingService
import org.javacs.kt.hover.hoverAt
import org.javacs.kt.position.offset
import org.javacs.kt.position.extractRange
import org.javacs.kt.position.position
import org.javacs.kt.references.findReferences
import org.javacs.kt.semantictokens.encodedSemanticTokens
import org.javacs.kt.signaturehelp.fetchSignatureHelpAt
import org.javacs.kt.rename.renameSymbol
import org.javacs.kt.highlight.documentHighlightsAt
import org.javacs.kt.inlayhints.provideHints
import org.javacs.kt.symbols.documentSymbols
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.Debouncer
import org.javacs.kt.util.TemporaryDirectory
import org.javacs.kt.util.describeURI
import org.javacs.kt.util.describeURIs
import org.javacs.kt.util.filePath
import org.javacs.kt.util.noResult
import org.javacs.kt.util.parseURI
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import java.net.URI
import java.io.Closeable
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture

class KotlinTextDocumentService(
    private val sf: SourceFiles,
    private val sp: SourcePath,
    private val config: Configuration,
    private val tempDirectory: TemporaryDirectory,
    private val uriContentProvider: URIContentProvider,
    private val cp: CompilerClassPath
) : TextDocumentService, Closeable {
    private lateinit var client: LanguageClient
    private val async = AsyncExecutor()
    private val formattingService = FormattingService(config.formatting)

    var debounceLint = Debouncer(Duration.ofMillis(config.diagnostics.debounceTime))
    private val lintLock = Any()
    private val publishedDiagnostics = mutableSetOf<URI>()
    val lintTodo = mutableSetOf<URI>()
    var lintCount = 0

    var lintRecompilationCallback: () -> Unit
        get() = sp.beforeCompileCallback
        set(callback) { sp.beforeCompileCallback = callback }

    private val TextDocumentItem.filePath: Path?
        get() = parseURI(uri).filePath

    private val TextDocumentIdentifier.filePath: Path?
        get() = parseURI(uri).filePath

    private val TextDocumentIdentifier.isKotlinScript: Boolean
        get() = uri.endsWith(".kts")

    private val TextDocumentIdentifier.content: String
        get() = sp.content(parseURI(uri))

    fun connect(client: LanguageClient) {
        this.client = client
    }

    private enum class SnapshotFreshness {
        CURRENT_REQUIRED,
        STALE_ALLOWED,
    }

    private fun recover(
        position: TextDocumentPositionParams,
        freshness: SnapshotFreshness,
    ): Pair<CompiledFile, Int>? = recover(position.textDocument.uri, position.position, freshness)

    private fun recover(uriString: String, position: Position, freshness: SnapshotFreshness): Pair<CompiledFile, Int>? {
        cp.awaitReady(parseURI(uriString).filePath)
        val uri = parseURI(uriString)
        if (!sf.isIncluded(uri)) {
            LOG.warn("URI is excluded, therefore cannot be recovered: $uri")
            return null
        }
        val content = sp.content(uri)
        val offset = offset(content, position.line, position.character)
        val compiled = when (freshness) {
            SnapshotFreshness.CURRENT_REQUIRED -> sp.currentVersion(uri)
            SnapshotFreshness.STALE_ALLOWED -> sp.latestCompiledVersion(uri)
        }
        return Pair(compiled, offset)
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> = async.compute {
        val (file, _) = recover(params.textDocument.uri, params.range.start, SnapshotFreshness.STALE_ALLOWED) ?: return@compute emptyList()
        codeActions(file, sp.index, params.range, params.context)
    }

    override fun inlayHint(params: InlayHintParams): CompletableFuture<List<InlayHint>> = async.compute {
        val (file, _) = recover(params.textDocument.uri, params.range.start, SnapshotFreshness.CURRENT_REQUIRED) ?: return@compute emptyList()
        provideHints(file, config.inlayHints)
    }

    override fun hover(position: HoverParams): CompletableFuture<Hover?> = async.compute {
        reportTime {
            LOG.info("Hovering at {}", describePosition(position))

            val (file, cursor) = recover(position, SnapshotFreshness.STALE_ALLOWED) ?: return@compute null
            hoverAt(file, cursor) ?: noResult("No hover found at ${describePosition(position)}", null)
        }
    }

    override fun documentHighlight(position: DocumentHighlightParams): CompletableFuture<List<DocumentHighlight>> = async.compute {
        val (file, cursor) = recover(position.textDocument.uri, position.position, SnapshotFreshness.STALE_ALLOWED) ?: return@compute emptyList()
        documentHighlightsAt(file, cursor)
    }

    override fun onTypeFormatting(params: DocumentOnTypeFormattingParams): CompletableFuture<List<TextEdit>> {
        TODO("not implemented")
    }

    override fun definition(position: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> = async.compute {
        reportTime {
            LOG.info("Go-to-definition at {}", describePosition(position))

            val (file, cursor) = recover(position, SnapshotFreshness.CURRENT_REQUIRED)
                ?: return@compute Either.forLeft(emptyList())
            goToDefinition(file, cursor, uriContentProvider.classContentProvider, tempDirectory, config.externalSources, cp)
                ?.let(::listOf)
                ?.let { Either.forLeft<List<Location>, List<LocationLink>>(it) }
                ?: noResult("Couldn't find definition at ${describePosition(position)}", Either.forLeft(emptyList()))
        }
    }

    override fun rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture<List<TextEdit>> = async.compute {
        val code = extractRange(params.textDocument.content, params.range)
        listOf(TextEdit(
            params.range,
            formattingService.formatKotlinCode(code, params.options)
        ))
    }

    override fun codeLens(params: CodeLensParams): CompletableFuture<List<CodeLens>> {
        TODO("not implemented")
    }

    override fun rename(params: RenameParams) = async.compute {
        val (file, cursor) = recover(params, SnapshotFreshness.STALE_ALLOWED) ?: return@compute null
        renameSymbol(file, cursor, sp, params.newName)
    }

    override fun completion(position: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> = async.compute {
        reportTime {
            LOG.info("Completing at {}", describePosition(position))

            val (file, cursor) = recover(position, SnapshotFreshness.STALE_ALLOWED) ?: return@compute Either.forRight(CompletionList()) // TODO: Investigate when to recompile
            val completions = completions(file, cursor, sp.index, config.completion)
            LOG.info("Found {} items", completions.items.size)

            Either.forRight(completions)
        }
    }

    override fun resolveCompletionItem(unresolved: CompletionItem): CompletableFuture<CompletionItem> {
        TODO("not implemented")
    }

    @Suppress("DEPRECATION")
    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> = async.compute {
        LOG.info("Find symbols in {}", describeURI(params.textDocument.uri))

        reportTime {
            val uri = parseURI(params.textDocument.uri)
            val parsed = sp.parsedFile(uri)

            documentSymbols(parsed)
        }
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        sf.open(uri, params.textDocument.text, params.textDocument.version)
        lintNow(uri)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        // Lint after saving to prevent inconsistent diagnostics
        val uri = parseURI(params.textDocument.uri)
        lintNow(uri)
        debounceLint.schedule {
            sp.save(uri)
        }
    }

    override fun signatureHelp(position: SignatureHelpParams): CompletableFuture<SignatureHelp?> = async.compute {
        reportTime {
            LOG.info("Signature help at {}", describePosition(position))

            val (file, cursor) = recover(position, SnapshotFreshness.STALE_ALLOWED) ?: return@compute null
            fetchSignatureHelpAt(file, cursor) ?: noResult("No function call around ${describePosition(position)}", null)
        }
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        sf.close(uri)
        clearDiagnostics(uri)
    }

    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> = async.compute {
        val code = params.textDocument.content
        LOG.info("Formatting {}", describeURI(params.textDocument.uri))
        listOf(TextEdit(
            Range(Position(0, 0), position(code, code.length)),
            formattingService.formatKotlinCode(code, params.options)
        ))
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        sf.edit(uri, params.textDocument.version, params.contentChanges)
        lintLater(uri)
    }

    override fun references(position: ReferenceParams) = async.compute {
        position.textDocument.filePath
            ?.let { file ->
                val content = sp.content(parseURI(position.textDocument.uri))
                val offset = offset(content, position.position.line, position.position.character)
                findReferences(file, offset, sp)
            }
    }

    override fun semanticTokensFull(params: SemanticTokensParams) = async.compute {
        LOG.info("Full semantic tokens in {}", describeURI(params.textDocument.uri))

        reportTime {
            val uri = parseURI(params.textDocument.uri)
            val file = sp.currentVersion(uri)

            val tokens = encodedSemanticTokens(file)
            LOG.info("Found {} tokens", tokens.size)

            SemanticTokens(tokens)
        }
    }

    override fun semanticTokensRange(params: SemanticTokensRangeParams) = async.compute {
        LOG.info("Ranged semantic tokens in {}", describeURI(params.textDocument.uri))

        reportTime {
            val uri = parseURI(params.textDocument.uri)
            val file = sp.currentVersion(uri)

            val tokens = encodedSemanticTokens(file, params.range)
            LOG.info("Found {} tokens", tokens.size)

            SemanticTokens(tokens)
        }
    }

    override fun resolveCodeLens(unresolved: CodeLens): CompletableFuture<CodeLens> {
        TODO("not implemented")
    }

    private fun describePosition(position: TextDocumentPositionParams): String {
        return "${describeURI(position.textDocument.uri)} ${position.position.line + 1}:${position.position.character + 1}"
    }

    public fun updateDebouncer() {
        val previous = debounceLint
        debounceLint = Debouncer(Duration.ofMillis(config.diagnostics.debounceTime))
        previous.shutdown(awaitTermination = false)
    }

    fun lintAll() {
        debounceLint.submitImmediately {
            sp.compileAllFiles()
            sp.saveAllFiles()
            sp.refreshDependencyIndexes()
        }
    }

    private fun clearLint(): List<URI> = synchronized(lintLock) {
        val result = lintTodo.toList()
        lintTodo.clear()
        result
    }

    private fun queueLint(uri: URI, immediately: Boolean) {
        synchronized(lintLock) { lintTodo.add(uri) }
        val task = { cancelCallback: () -> Boolean -> doLint(cancelCallback) }
        if (immediately) debounceLint.submitImmediately(task) else debounceLint.schedule(task)
    }

    private fun lintLater(uri: URI) {
        queueLint(uri, immediately = false)
    }

    private fun lintNow(file: URI) {
        queueLint(file, immediately = true)
    }

    private fun doLint(cancelCallback: () -> Boolean) {
        cp.awaitReady()
        val files = clearLint()
        if (files.isEmpty()) return
        LOG.info("Refreshing semantic state for {}", describeURIs(files))
        val context = sp.compileFiles(files)
        if (!cancelCallback.invoke()) {
            val currentFiles = files.filter { sp.snapshotState(it) == SemanticSnapshotState.CURRENT }
            if (config.diagnostics.enabled) {
                reportDiagnostics(currentFiles, context.diagnostics)
            } else {
                currentFiles.filter { sf.isOpen(it) && it in publishedDiagnostics }.forEach(::clearDiagnostics)
            }
        }
        lintCount++
    }

    private fun reportDiagnostics(compiled: Collection<URI>, kotlinDiagnostics: Diagnostics) {
        val langServerDiagnostics = kotlinDiagnostics
            .flatMap(::convertDiagnostic)
            .filter { it.first in compiled }
            .filter { config.diagnostics.enabled && it.second.severity <= config.diagnostics.level }
        val byFile = langServerDiagnostics.groupBy({ it.first }, { it.second })

        for ((uri, diagnostics) in byFile) {
            if (sf.isOpen(uri)) {
                client.publishDiagnostics(PublishDiagnosticsParams(uri.toString(), diagnostics))
                publishedDiagnostics.add(uri)

                LOG.info("Reported {} diagnostics in {}", diagnostics.size, describeURI(uri))
            }
            else LOG.info("Ignore {} diagnostics in {} because it's not open", diagnostics.size, describeURI(uri))
        }

        val noErrors = compiled - byFile.keys
        for (file in noErrors) {
            clearDiagnostics(file)

            LOG.info("No diagnostics in {}", file)
        }
    }

    private fun clearDiagnostics(uri: URI) {
        client.publishDiagnostics(PublishDiagnosticsParams(uri.toString(), listOf()))
        publishedDiagnostics.remove(uri)
    }

    private fun shutdownExecutors(awaitTermination: Boolean) {
        async.shutdown(awaitTermination)
        debounceLint.shutdown(awaitTermination)
    }

    override fun close() {
        shutdownExecutors(awaitTermination = true)
    }
}

private inline fun<T> reportTime(block: () -> T): T {
    val started = System.currentTimeMillis()
    try {
        return block()
    } finally {
        val finished = System.currentTimeMillis()
        LOG.info("Finished in {} ms", finished - started)
    }
}
