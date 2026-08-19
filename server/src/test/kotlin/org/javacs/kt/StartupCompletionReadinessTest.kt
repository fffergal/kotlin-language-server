package org.javacs.kt

import com.google.gson.JsonObject
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.InitializedParams
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.junit.Test
import java.util.concurrent.TimeUnit

class StartupCompletionReadinessTest : LanguageServerTestFixture("Workspace", startProjectModel = false) {
    private val projectAFile = "ProjectA/src/UsesSharedLibrary.kt"

    @Test fun `startup completion waits for configuration and project model readiness`() {
        languageServer.initialized(InitializedParams())
        open(projectAFile)

        val completion = languageServer.textDocumentService.completion(completionParams(projectAFile, 8, 20))
        Thread.sleep(100)
        assertThat(completion.isDone, equalTo(false))

        languageServer.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(JsonObject()))
        val labels = completion.get(5, TimeUnit.SECONDS).right.items.map { it.label }
        assertThat(labels, hasItem("sharedFunction()"))
    }
}
