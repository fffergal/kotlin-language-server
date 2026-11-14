package org.javacs.kt.signaturehelp

import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Tuple
import org.javacs.kt.SignatureHelpConfiguration
import org.javacs.kt.CompiledFile
import org.javacs.kt.completion.DECL_RENDERER
import org.javacs.kt.completion.identifierOverloads
import org.javacs.kt.completion.memberOverloads
import org.javacs.kt.docs.findDoc
import org.javacs.kt.util.findParent
import org.javacs.kt.util.nullResult
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithSource
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.javacs.kt.LOG

fun fetchSignatureHelpAt(
    file: CompiledFile,
    cursor: Int,
    config: SignatureHelpConfiguration = SignatureHelpConfiguration(),
): SignatureHelp? {
    val (signatures, activeSignature, activeParameter) =
        getSignatureTriplet(file, cursor, config) ?: return nullResult("No call around ${file.describePosition(cursor)}")
    return SignatureHelp(signatures, activeSignature, activeParameter)
}

/**
 * Returns the doc string of the first found CallableDescriptor
 *
 * Avoids fetching the SignatureHelp triplet due to an OutOfBoundsException that can occur due to the offset difference math.
 * When hovering, the cursor param is set to the doc offset where the mouse is hovering over, rather than where the actual cursor is,
 * hence this is seen to cause issues when slicing the param list string
 */
fun getDocString(file: CompiledFile, cursor: Int): String {
    val signatures = getSignatures(file, cursor)
    if (signatures == null || signatures.size == 0 || signatures[0].documentation == null)
        return ""
    return if (signatures[0].documentation.isLeft()) signatures[0].documentation.left else ""
}

// TODO better function name?
@Suppress("ReturnCount")
private fun getSignatureTriplet(
    file: CompiledFile,
    cursor: Int,
    config: SignatureHelpConfiguration,
): Triple<List<SignatureInformation>, Int?, Int?>? {
    val call = file.parseAtPoint(cursor)?.findParent<KtCallExpression>() ?: return null
    val candidates = candidates(call, file)
    if (candidates.isEmpty()) {
        return null
    }
    val activeSignature = activeSignature(call, candidates)
    val signatures = candidates.map { candidate -> toSignature(candidate, call, cursor, config) }
    val activeParameter = activeSignature?.let { signatures[it].activeParameter }
        ?: candidates.firstNotNullOfOrNull { activeParameter(call, cursor, it) }

    return Triple(signatures, activeSignature, activeParameter)
}

private fun getSignatures(file: CompiledFile, cursor: Int): List<SignatureInformation>? {
    val call = file.parseAtPoint(cursor)?.findParent<KtCallExpression>() ?: return null
    val candidates = candidates(call, file)
    return candidates.map { toSignature(it, call, cursor, SignatureHelpConfiguration()) }
}

private fun toSignature(
    desc: CallableDescriptor,
    call: KtCallExpression,
    cursor: Int,
    config: SignatureHelpConfiguration,
): SignatureInformation {
    val label = DECL_RENDERER.render(desc)
    val ranges = parameterRanges(label, desc.valueParameters.size)
    val params = desc.valueParameters.mapIndexedNotNull { index, parameter ->
        ranges.getOrNull(index)?.let { range -> toParameter(parameter, label, range, config.labelOffsetSupport) }
    }
    val signature = SignatureInformation(label, docstring(desc), params)
    if (config.activeParameterSupport) {
        signature.activeParameter = activeParameter(call, cursor, desc)
    }
    return signature
}

private fun toParameter(
    parameter: ValueParameterDescriptor,
    signatureLabel: String,
    range: IntRange,
    labelOffsetSupport: Boolean,
): ParameterInformation {
    val start = range.first
    val end = range.last + 1
    return ParameterInformation().apply {
        label = if (labelOffsetSupport) {
            Either.forRight(Tuple.two(start, end))
        } else {
            Either.forLeft(signatureLabel.substring(start, end))
        }
        documentation = Either.forLeft(docstring(parameter))
    }
}

private fun docstring(declaration: DeclarationDescriptorWithSource): String {
    val doc = findDoc(declaration) ?: return ""

    return doc.getContent().trim()
}

@Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
internal fun parameterRanges(label: String, parameterCount: Int): List<IntRange> {
    if (parameterCount == 0) return emptyList()
    val open = label.indexOf('(')
    if (open < 0) return emptyList()
    val close = matchingCloseParenthesis(label, open)
    if (close <= open + 1) return emptyList()

    val ranges = mutableListOf<IntRange>()
    var start = open + 1
    var parentheses = 0
    var angles = 0
    var brackets = 0
    var braces = 0
    for (index in start until close) {
        when (label[index]) {
            '(' -> parentheses++
            ')' -> parentheses--
            '<' -> angles++
            '>' -> if (angles > 0) angles--
            '[' -> brackets++
            ']' -> brackets--
            '{' -> braces++
            '}' -> braces--
            ',' -> if (parentheses == 0 && angles == 0 && brackets == 0 && braces == 0) {
                trimmedRange(label, start, index)?.let(ranges::add)
                start = index + 1
            }
        }
    }
    trimmedRange(label, start, close)?.let(ranges::add)
    return ranges.takeIf { it.size == parameterCount }.orEmpty()
}

private fun matchingCloseParenthesis(label: String, open: Int): Int {
    var depth = 0
    for (index in open until label.length) {
        when (label[index]) {
            '(' -> depth++
            ')' -> if (--depth == 0) return index
        }
    }
    return -1
}

private fun trimmedRange(label: String, rawStart: Int, rawEnd: Int): IntRange? {
    var start = rawStart
    var end = rawEnd
    while (start < end && label[start].isWhitespace()) start++
    while (end > start && label[end - 1].isWhitespace()) end--
    return if (start < end) start until end else null
}

private fun candidates(call: KtCallExpression, file: CompiledFile): List<CallableDescriptor> {
    val target = call.calleeExpression!!
    val identifier = target.text // TODO when call is foo.bar(), is this bar or foo.bar ?
    val dotParent = target.findParent<KtDotQualifiedExpression>()
    if (dotParent != null) {
        val type = file.typeAtPoint(dotParent.receiverExpression.startOffset) ?: return emptyList()

        return memberOverloads(type, identifier).toList()
    }
    val idParent = target.findParent<KtNameReferenceExpression>()
    if (idParent != null) {
        val scope = file.scopeAtPoint(idParent.startOffset) ?: return emptyList()

        return identifierOverloads(scope, identifier).toList()
    }
    return emptyList()
}

private fun activeSignature(call: KtCallExpression, candidates: List<CallableDescriptor>): Int? {
    val activeIndex = candidates.indexOfFirst { isCompatibleWith(call, it) }
    if (activeIndex < 0) {
        LOG.warn("No activeSignature found, omitting from SignatureHelp response.")
        return null
    }
    return activeIndex
}

private fun isCompatibleWith(call: KtCallExpression, candidate: CallableDescriptor): Boolean {
    val argumentList = call.valueArgumentList ?: return true
    val nArguments = argumentList.text.count { it == ',' } + 1
    if (nArguments > candidate.valueParameters.size)
        return false

    for (arg in call.valueArguments) {
        if (arg.isNamed()) {
            if (candidate.valueParameters.none { arg.name == it.name.identifier })
                return false
        }
        // TODO consider types as well
    }

    return true
}

@Suppress("ReturnCount")
private fun activeParameter(
    call: KtCallExpression,
    cursor: Int,
    candidate: CallableDescriptor,
): Int? {
    if (candidate.valueParameters.isEmpty()) return null
    val args = call.valueArgumentList ?: return null
    val text = args.text
    val rawIndex = if (text.length == 2) {
        0
    } else {
        val min = minOf(args.textRange.startOffset, cursor)
        val max = maxOf(args.textRange.startOffset, cursor)
        text.subSequence(0, max - min).count { it == ',' }
    }
    val currentArgument = call.valueArguments.getOrNull(rawIndex)
    val namedIndex = currentArgument?.takeIf { it.isNamed() }?.let { argument ->
        candidate.valueParameters.indexOfFirst { it.name.identifier == argument.name }
            .takeIf { it >= 0 }
    }
    return namedIndex ?: rawIndex.coerceAtMost(candidate.valueParameters.lastIndex)
}
