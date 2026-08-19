package org.javacs.kt

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.hasItem
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SemanticRefreshTest : LanguageServerTestFixture(
    "Workspace",
    Configuration().apply {
        diagnostics.enabled = false
        diagnostics.debounceTime = 500
    }
) {
    private val consumer = "ProjectA/src/UsesSharedLibrary.kt"
    private val dependency = "ProjectB/src/SharedLibrary.kt"

    @Test fun `didOpen warms semantics without publishing disabled diagnostics`() {
        val compilations = AtomicInteger()
        languageServer.textDocumentService.lintRecompilationCallback = { compilations.incrementAndGet() }

        open(consumer)
        languageServer.textDocumentService.debounceLint.waitForPendingTask()

        assertThat(compilations.get(), greaterThan(0))
        assertThat(languageServer.textDocumentService.lintCount, equalTo(1))
        assertThat(diagnosticPublicationCount, equalTo(0))
    }

    @Test fun `rapid edits coalesce into one semantic refresh when diagnostics are disabled`() {
        open(consumer)
        languageServer.textDocumentService.debounceLint.waitForPendingTask()
        val initialRefreshes = languageServer.textDocumentService.lintCount

        repeat(10) { replace(consumer, 1, 1, "", " ") }
        languageServer.textDocumentService.debounceLint.waitForPendingTask()

        assertThat(languageServer.textDocumentService.lintCount, equalTo(initialRefreshes + 1))
        assertThat(diagnosticPublicationCount, equalTo(0))
    }

    @Test fun `completion uses previous semantic snapshot before idle refresh`() {
        val compilations = AtomicInteger()
        languageServer.textDocumentService.lintRecompilationCallback = { compilations.incrementAndGet() }
        open(consumer)
        languageServer.textDocumentService.debounceLint.waitForPendingTask()
        val warmedCompilations = compilations.get()

        replace(consumer, 8, 20, "sharedFunction()", "")
        val labels = languageServer.textDocumentService
            .completion(completionParams(consumer, 8, 20))
            .get().right.items.map { it.label }

        assertThat(labels, hasItem("sharedFunction()"))
        assertThat(compilations.get(), equalTo(warmedCompilations))
    }

    @Test fun `imported source edit refreshes open consumer without diagnostics`() {
        open(consumer)
        languageServer.textDocumentService.debounceLint.waitForPendingTask()
        open(dependency)
        languageServer.textDocumentService.debounceLint.waitForPendingTask()

        replace(dependency, 5, 1, "", "    fun addedFunction(): String = \"added\"\n")
        languageServer.textDocumentService.debounceLint.waitForPendingTask()

        replace(consumer, 8, 20, "sharedFunction()", "")
        val labels = languageServer.textDocumentService
            .completion(completionParams(consumer, 8, 20))
            .get().right.items.map { it.label }

        assertThat(labels, hasItem("addedFunction()"))
        assertThat(diagnosticPublicationCount, equalTo(0))
    }

    @Test fun `dependency edit retains stale consumer for completion until workspace refresh`() {
        val sourcePath = languageServer.sourcePath
        val consumerUri = uri(consumer)
        val dependencyUri = uri(dependency)
        val compilations = AtomicInteger()
        languageServer.textDocumentService.lintRecompilationCallback = { compilations.incrementAndGet() }

        open(consumer)
        languageServer.textDocumentService.debounceLint.waitForPendingTask()
        assertThat(sourcePath.snapshotState(consumerUri), equalTo(SemanticSnapshotState.CURRENT))

        val dependencyContent = sourcePath.content(dependencyUri)
        sourcePath.put(
            dependencyUri,
            dependencyContent.replace(
                "    fun sharedFunction(): String = \"shared\"",
                "    fun addedFunction(): String = \"added\"\n    fun sharedFunction(): String = \"shared\""
            ),
            null
        )
        assertThat(sourcePath.snapshotState(consumerUri), equalTo(SemanticSnapshotState.STALE))

        sourcePath.put(
            consumerUri,
            sourcePath.content(consumerUri).replace("sharedFunction()", ""),
            null
        )
        val beforeCompletion = compilations.get()
        val staleLabels = languageServer.textDocumentService
            .completion(completionParams(consumer, 8, 20))
            .get().right.items.map { it.label }

        assertThat(staleLabels, hasItem("sharedFunction()"))
        assertThat(compilations.get(), equalTo(beforeCompletion))
        assertThat(sourcePath.snapshotState(consumerUri), equalTo(SemanticSnapshotState.STALE))

        sourcePath.compileFiles(listOf(dependencyUri))
        assertThat(sourcePath.snapshotState(consumerUri), equalTo(SemanticSnapshotState.CURRENT))

        val refreshedLabels = languageServer.textDocumentService
            .completion(completionParams(consumer, 8, 20))
            .get().right.items.map { it.label }
        assertThat(refreshedLabels, hasItem("addedFunction()"))
    }
}
