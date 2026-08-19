package org.javacs.kt

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.startsWith
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CrossProjectNavigationTest : LanguageServerTestFixture(
    "Workspace",
    Configuration().apply { diagnostics.enabled = false }
) {
    private val projectAFile = "ProjectA/src/UsesSharedLibrary.kt"

    @Test fun `inferred receiver definition uses semantic compilation and reaches ProjectB source`() {
        val compilations = AtomicInteger()
        languageServer.textDocumentService.lintRecompilationCallback = { compilations.incrementAndGet() }
        open(projectAFile)
        languageServer.textDocumentService.debounceLint.waitForPendingTask()

        val result = languageServer.textDocumentService
            .definition(definitionParams(projectAFile, 8, 25))
            .get()

        assertThat(compilations.get(), greaterThan(0))
        val locations = result.left
        assertThat(locations.size, equalTo(1))
        assertThat(
            locations.single().uri,
            equalTo(workspaceRoot.resolve("ProjectB/src/SharedLibrary.kt").toUri().toString())
        )
        assertThat(locations.single().uri, startsWith("file:"))
    }

    @Test fun `inferred receiver completion uses semantic compilation`() {
        val compilations = AtomicInteger()
        languageServer.textDocumentService.lintRecompilationCallback = { compilations.incrementAndGet() }
        open(projectAFile)
        languageServer.textDocumentService.debounceLint.waitForPendingTask()

        val labels = languageServer.textDocumentService
            .completion(completionParams(projectAFile, 8, 20))
            .get().right.items.map { it.label }

        assertThat(compilations.get(), greaterThan(0))
        assertThat(labels, hasItem("sharedFunction()"))
    }

    @Test fun `nested imported receiver completes through semantic fallback`() {
        val file = "ProjectA/src/UsesNested.kt"
        val compilations = AtomicInteger()
        languageServer.textDocumentService.lintRecompilationCallback = { compilations.incrementAndGet() }
        open(file)
        languageServer.textDocumentService.debounceLint.waitForPendingTask()

        val labels = languageServer.textDocumentService
            .completion(completionParams(file, 7, 19))
            .get().right.items.map { it.label }

        assertThat(compilations.get(), greaterThan(0))
        assertThat(labels, hasItem("nestedFunction()"))
    }

    @Test fun `explicit imported definitions stay source backed across sequential files`() {
        val nestedFile = "ProjectA/src/UsesNested.kt"
        open(projectAFile)
        languageServer.textDocumentService.debounceLint.waitForPendingTask()

        val sharedLocations = languageServer.textDocumentService
            .definition(definitionParams(projectAFile, 3, 30))
            .get().left
        assertThat(sharedLocations.size, equalTo(1))
        assertThat(
            sharedLocations.single().uri,
            equalTo(workspaceRoot.resolve("ProjectB/src/SharedLibrary.kt").toUri().toString())
        )

        open(nestedFile)
        languageServer.textDocumentService.debounceLint.waitForPendingTask()
        val nestedLocations = languageServer.textDocumentService
            .definition(definitionParams(nestedFile, 3, 34))
            .get().left
        assertThat(nestedLocations.size, equalTo(1))
        assertThat(
            nestedLocations.single().uri,
            equalTo(workspaceRoot.resolve("ProjectB/src/Outer.kt").toUri().toString())
        )
    }

    @Test fun `first completion creates workspace source snapshot without binary first pass`() {
        val fileUri = uri(projectAFile)
        val compilations = AtomicInteger()
        languageServer.textDocumentService.lintRecompilationCallback = { compilations.incrementAndGet() }
        assertThat(languageServer.sourcePath.snapshotState(fileUri), equalTo(SemanticSnapshotState.ABSENT))

        val labels = languageServer.textDocumentService
            .completion(completionParams(projectAFile, 8, 20))
            .get().right.items.map { it.label }

        assertThat(labels, hasItem("sharedFunction()"))
        assertThat(compilations.get(), equalTo(1))
        assertThat(languageServer.sourcePath.snapshotState(fileUri), equalTo(SemanticSnapshotState.CURRENT))

        val locations = languageServer.textDocumentService
            .definition(definitionParams(projectAFile, 3, 30))
            .get().left
        assertThat(
            locations.single().uri,
            equalTo(workspaceRoot.resolve("ProjectB/src/SharedLibrary.kt").toUri().toString())
        )
        assertThat(compilations.get(), equalTo(1))
    }
}
