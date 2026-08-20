package org.javacs.kt.definition

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import java.nio.file.Path
import org.javacs.kt.CompiledFile
import org.javacs.kt.CompilerClassPath
import org.javacs.kt.LOG
import org.javacs.kt.ExternalSourcesConfiguration
import org.javacs.kt.externalsources.ClassContentProvider
import org.javacs.kt.externalsources.toKlsURI
import org.javacs.kt.externalsources.KlsURI
import org.javacs.kt.position.location
import org.javacs.kt.position.isZero
import org.javacs.kt.position.position
import org.javacs.kt.util.partitionAroundLast
import org.javacs.kt.util.TemporaryDirectory
import org.javacs.kt.util.parseURI
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import java.io.File
import java.nio.file.Paths
import java.util.Collections
import java.util.IdentityHashMap

private val cachedTempFiles = mutableMapOf<KlsURI, Path>()
private val definitionPattern = Regex("(?:class|interface|object|fun)\\s+(\\w+)")

fun goToDefinition(
    file: CompiledFile,
    cursor: Int,
    classContentProvider: ClassContentProvider,
    tempDir: TemporaryDirectory,
    config: ExternalSourcesConfiguration,
    cp: CompilerClassPath
): Location? {
    val (_, target) = file.referenceExpressionAtPoint(cursor) ?: return null

    LOG.info("Found declaration descriptor {}", target)
    val resolved = resolveDefinition(target)
    if (resolved != null) {
        val selected = resolved.descriptor
        val destination = resolved.location

        if (selected !== target) {
            LOG.debug("Resolved definition for synthetic descriptor {} through overridden descriptor {}", target, selected)
        }

        val rawClassURI = destination.uri
        if (isInsideArchive(rawClassURI, cp)) {
            parseURI(rawClassURI).toKlsURI()?.let { klsURI ->
                val (klsSourceURI, content) = classContentProvider.contentOf(klsURI)

                if (config.useKlsScheme) {
                    // Defer decompilation until a jarClassContents request is sent
                    destination.uri = klsSourceURI.toString()
                } else {
                    // Return the path to a temporary file
                    // since the client has not opted into
                    // or does not support KLS URIs
                    val tmpFile = cachedTempFiles[klsSourceURI] ?: run {
                        val name = klsSourceURI.fileName.partitionAroundLast(".").first
                        val extensionWithoutDot = klsSourceURI.fileExtension
                        val extension = if (extensionWithoutDot != null) ".$extensionWithoutDot" else ""
                        tempDir.createTempFile(name, extension)
                            .also {
                                it.toFile().writeText(content)
                                cachedTempFiles[klsSourceURI] = it
                            }
                    }

                    destination.uri = tmpFile.toUri().toString()
                }

                if (destination.range.isZero) {
                    // Try to find the definition inside the source directly
                    val name = when (selected) {
                        is ConstructorDescriptor -> selected.constructedClass.name.toString()
                        else -> selected.name.toString()
                    }
                    definitionPattern.findAll(content)
                        .map { it.groups[1]!! }
                        .find { it.value == name }
                        ?.let { it.range }
                        ?.let { destination.range = Range(position(content, it.first), position(content, it.last)) }
                }
            }
        }
    }

    return resolved?.location
}

private data class ResolvedDefinition(
    val descriptor: DeclarationDescriptor,
    val location: Location,
)

private fun resolveDefinition(target: DeclarationDescriptor): ResolvedDefinition? {
    val direct = descriptorLocation(target)
    if (direct != null || target !is CallableMemberDescriptor) {
        return direct?.let { ResolvedDefinition(target, it) }
    }

    val visited = Collections.newSetFromMap(IdentityHashMap<DeclarationDescriptor, Boolean>())
    val pending = ArrayDeque<DeclarationDescriptor>()
    var sourceFallback: ResolvedDefinition? = null
    var externalFallback: ResolvedDefinition? = null
    visited.add(target)
    target.overriddenDescriptors.forEach(pending::addLast)

    while (pending.isNotEmpty() && sourceFallback == null) {
        val candidate = pending.removeFirst()
        if (!visited.add(candidate)) continue

        sourceFallback = sourceDeclarationLocation(candidate)?.let { ResolvedDefinition(candidate, it) }
        if (externalFallback == null) {
            location(candidate)?.let { externalFallback = ResolvedDefinition(candidate, it) }
        }
        (candidate as? CallableMemberDescriptor)?.overriddenDescriptors?.forEach(pending::addLast)
    }
    return sourceFallback ?: externalFallback
}

private fun descriptorLocation(descriptor: DeclarationDescriptor): Location? =
    sourceDeclarationLocation(descriptor) ?: location(descriptor)

private fun sourceDeclarationLocation(descriptor: DeclarationDescriptor): Location? =
    (descriptor.findPsi() as? KtNamedDeclaration)?.nameIdentifier?.let(::location)

private fun isInsideArchive(uri: String, cp: CompilerClassPath) =
    uri.contains(".jar!") || uri.contains(".zip!") || cp.javaHome?.let {
        Paths.get(parseURI(uri)).toString().startsWith(File(it).path)
    } ?: false
