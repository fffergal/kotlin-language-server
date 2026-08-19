package org.javacs.kt

import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent
import org.hamcrest.Matchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test

class AdditionalWorkspaceTest : LanguageServerTestFixture("mainWorkspace") {
    val file = "MainWorkspaceFile.kt"

    fun addWorkspaceRoot() {
        val folder = WorkspaceFolder()
        folder.uri = absoluteWorkspaceRoot("additionalWorkspace").toUri().toString()

        val addWorkspace = DidChangeWorkspaceFoldersParams()
        addWorkspace.event = WorkspaceFoldersChangeEvent()
        addWorkspace.event.added = listOf(folder)

        languageServer.workspaceService.didChangeWorkspaceFolders(addWorkspace)
    }

    @Test fun `junit should be on classpath`() {
        addWorkspaceRoot()
        open(file)

        val hover = languageServer.textDocumentService.hover(hoverParams(file, 5, 14)).get()!!
        assertThat(hover.contents.right.value, containsString("fun assertTrue"))
    }

    @Ignore // TODO
    @Test fun `recompile all when classpath changes`() {
        open(file)

        val hover = languageServer.textDocumentService.hover(hoverParams(file, 5, 14)).get()
        assertThat("No hover before JUnit is added to classpath", hover, nullValue())

        addWorkspaceRoot()
        val hoverAgain = languageServer.textDocumentService.hover(hoverParams(file, 5, 14)).get() ?: return fail("No hover")
        assertThat(hoverAgain.contents.right.value, containsString("fun assertTrue"))
    }
}


class OverlappingWorkspaceTest : LanguageServerTestFixture("Workspace") {
    private val child = workspaceRoot.resolve("ProjectA").toAbsolutePath().normalize()

    @Test fun `nested workspace addition keeps one effective source root`() {
        changeWorkspaceFolders(added = listOf(child))

        assertThat(languageServer.sourceFiles.workspaceRootPaths, equalTo(setOf(workspaceRoot.toAbsolutePath().normalize())))
        assertThat(languageServer.sourceFiles.requestedWorkspaceRootPaths, hasItem(child))
    }

    @Test fun `removing parent retains still requested child sources`() {
        changeWorkspaceFolders(added = listOf(child))
        changeWorkspaceFolders(removed = listOf(workspaceRoot.toAbsolutePath().normalize()))

        assertThat(languageServer.sourceFiles.workspaceRootPaths, equalTo(setOf(child)))
        assertThat(languageServer.sourceFiles.isIncluded(child.resolve("src/UsesSharedLibrary.kt").toUri()), equalTo(true))
    }

    private fun changeWorkspaceFolders(added: List<java.nio.file.Path> = emptyList(), removed: List<java.nio.file.Path> = emptyList()) {
        val event = WorkspaceFoldersChangeEvent().apply {
            this.added = added.map { path -> WorkspaceFolder(path.toUri().toString(), path.fileName.toString()) }
            this.removed = removed.map { path -> WorkspaceFolder(path.toUri().toString(), path.fileName.toString()) }
        }
        languageServer.workspaceService.didChangeWorkspaceFolders(DidChangeWorkspaceFoldersParams(event))
    }
}
