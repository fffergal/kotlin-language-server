package org.javacs.kt
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.Test

class ImplementationTest : SingleFileTestFixture("implementation", "Implementations.kt") {

    @Test fun `server advertises implementation provider`() {
        assertThat(initializeResult.capabilities.implementationProvider.left, equalTo(true))
    }

    @Test fun `interface declaration finds deterministic transitive class implementations`() {
        assertImplementationLines(1, 13, 8, 10, 15, 23, 28, 30, 1)
    }

    @Test fun `base class finds transitive subclasses`() {
        assertImplementationLines(34, 15, 35, 36)
    }

    @Test fun `interface function declaration finds only explicit source overrides`() {
        assertImplementationLines(2, 12, 11, 16, 24, 2)
    }

    @Test fun `interface property declaration finds explicit source overrides`() {
        assertImplementationLines(3, 12, 12, 17, 25, 3)
    }

    @Test fun `reference site resolves the same function implementations`() {
        assertImplementationLines(41, 66, 11, 16, 24, 2)
    }

    @Test fun `delegated fake override cursor normalizes to source declaration`() {
        assertImplementationLines(31, 40, 11, 16, 24, 2)
    }

    @Test fun `diamond inheritance returns each implementation once`() {
        val locations = implementations(2, 12)
        assertThat(locations.count { it.range.start.line == 23 }, equalTo(1))
    }

    @Test fun `declaration without implementations returns empty result`() {
        assertThat(implementations(38, 13), empty())
    }

    @Test fun `source edit is compiled before implementation results are returned`() {
        assertThat(implementations(38, 13), empty())

        replace(file, 39, 28, "", " : Unimplemented")

        assertImplementationLines(38, 13, 39)
    }

    private fun implementations(line: Int, column: Int) = languageServer.textDocumentService
        .implementation(implementationParams(file, line, column))
        .get().left

    private fun assertImplementationLines(line: Int, column: Int, vararg expectedLines: Int) {
        val locations = implementations(line, column)
        assertThat(locations, hasSize(expectedLines.size))
        assertThat(locations.map { it.range.start.line + 1 }, equalTo(expectedLines.toList()))
    }
}
