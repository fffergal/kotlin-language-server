package org.javacs.kt

import org.hamcrest.Matchers.*
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.SignatureInformation
import org.javacs.kt.signaturehelp.parameterRanges
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Ignore
import org.junit.Test

class SignatureHelpTest : SingleFileTestFixture("signatureHelp", "SignatureHelp.kt") {
    @Test fun `provide signature help for a function call`() {
        val help = languageServer.textDocumentService.signatureHelp(signatureHelpParams(file, 3, 20)).get()!!

        assertThat(help.signatures, hasSize(2))
        assertThat(help.activeParameter, equalTo(0))
        assertThat(help.activeSignature, equalTo(0))
        assertThat(help.signatures.map { it.label }, hasItems("fun foo(bar: String): Unit", "fun foo(bar: Int): Unit"))
        assertThat(help.signatures.map { it.documentation.left }, hasItems("Call foo with a String", "Call foo with an Int"))
        assertThat(help.signatures.flatMap(::parameterTexts), hasItems("bar: String", "bar: Int"))
        assertThat(help.signatures.flatMap { it.parameters.map { it.documentation.left }}, hasItems("String param", "Int param"))
    }

    @Test fun `provide signature help for a constructor`() {
        val help = languageServer.textDocumentService.signatureHelp(signatureHelpParams(file, 4, 21)).get()!!

        assertThat(help.signatures, hasSize(2))
        assertThat(help.activeParameter, equalTo(0))
        assertThat(help.activeSignature, equalTo(0))
        assertThat(help.signatures.map { it.label }, hasItems("constructor Constructor(bar: String)", "constructor Constructor(bar: Int)"))
        assertThat(help.signatures.map { it.documentation.left }, hasItems("Construct with a String", "Construct with an Int"))
        assertThat(help.signatures.flatMap(::parameterTexts), hasItems("bar: String", "bar: Int"))
        assertThat(help.signatures.flatMap { it.parameters.map { it.documentation.left }}, hasItems("String param", "Int param"))
    }

    @Test fun `find active parameter`() {
        val help = languageServer.textDocumentService.signatureHelp(signatureHelpParams(file, 5, 32)).get()!!

        assertThat(help.activeParameter, equalTo(1))
    }

    @Test fun `find active parameter in the middle of list`() {
        val help = languageServer.textDocumentService.signatureHelp(signatureHelpParams(file, 5, 30)).get()!!

        assertThat(help.activeParameter, equalTo(0))
    }

    @Ignore @Test fun `find active signature using types`() {
        val help = languageServer.textDocumentService.signatureHelp(signatureHelpParams(file, 5, 32)).get()!!

        assertThat(help.signatures[help.activeSignature].label, equalTo("fun multiParam(first: String, second: String): Unit"))
    }

    @Test fun `find active signature using number of arguments`() {
        val help = languageServer.textDocumentService.signatureHelp(signatureHelpParams(file, 6, 34)).get()!!

        assertThat(help.signatures[help.activeSignature].label, equalTo("fun oneOrTwoArgs(first: String, second: String): Unit"))
    }

    @Test fun `get overload parameter ranges are valid and extract their own labels`() {
        val help = languageServer.textDocumentService.signatureHelp(signatureHelpParams(file, 9, 20)).get()!!

        assertThat(help.signatures, hasSize(2))
        help.signatures.forEach(::assertValidParameterRanges)
        assertThat(help.signatures.flatMap(::parameterTexts), containsInAnyOrder("index: Int", "fieldName: String"))
        val javaStyleLabel = "fun get(Int): JsonNode!"
        val javaStyleRange = parameterRanges(javaStyleLabel, 1).single()
        assertThat(javaStyleLabel.substring(javaStyleRange), equalTo("Int"))
    }

    @Test fun `active parameter is valid for each differing overload layout`() {
        val help = languageServer.textDocumentService.signatureHelp(signatureHelpParams(file, 7, 38)).get()!!
        val byParameterCount = help.signatures.associateBy { it.parameters.size }

        assertThat(byParameterCount.getValue(1).activeParameter, equalTo(0))
        assertThat(byParameterCount.getValue(3).activeParameter, equalTo(1))
        assertThat(help.activeParameter, equalTo(1))
        help.signatures.forEach(::assertValidParameterRanges)
    }

    @Test fun `parameter parser preserves commas nested in function types`() {
        val help = languageServer.textDocumentService.signatureHelp(signatureHelpParams(file, 8, 46)).get()!!
        val signature = help.signatures.single()

        assertValidParameterRanges(signature)
        assertThat(parameterTexts(signature), contains("callback: (Int, String) -> Unit", "value: String"))
    }

    @Test fun `parameter offsets use UTF-16 code units`() {
        val label = "fun `😀unicode`(`値😀`: String): Unit"
        val range = parameterRanges(label, 1).single()
        val text = label.substring(range)

        assertThat(text, equalTo("`値😀`: String"))
        assertThat(range.first, greaterThan(label.codePointCount(0, range.first)))
        assertThat(range.last + 1, lessThanOrEqualTo(label.length))
    }

    private fun assertValidParameterRanges(signature: SignatureInformation) {
        signature.parameters.forEach { parameter ->
            assertThat(parameter.label.isRight, equalTo(true))
            val range = parameter.label.right
            assertThat(range.first, greaterThanOrEqualTo(0))
            assertThat(range.first, lessThan(range.second))
            assertThat(range.second, lessThanOrEqualTo(signature.label.length))
            assertThat(signature.label.substring(range.first, range.second), not(isEmptyString()))
        }
    }

    private fun parameterTexts(signature: SignatureInformation): List<String> =
        signature.parameters.map { parameterText(signature, it) }

    private fun parameterText(signature: SignatureInformation, parameter: ParameterInformation): String =
        if (parameter.label.isLeft) parameter.label.left
        else parameter.label.right.let { signature.label.substring(it.first, it.second) }
}
