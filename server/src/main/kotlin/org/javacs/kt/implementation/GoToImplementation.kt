package org.javacs.kt.implementation

import org.eclipse.lsp4j.Location
import org.javacs.kt.CompiledFile
import org.javacs.kt.WorkspaceCompilation
import org.javacs.kt.position.location
import org.javacs.kt.util.findParent
import org.javacs.kt.util.preOrderTraversal
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.resolve.BindingContext
import java.util.Collections
import java.util.IdentityHashMap

internal fun findImplementations(workspace: WorkspaceCompilation, cursor: Int): List<Location> {
    val target = targetDescriptor(workspace.requestedFile, cursor)?.sourceDeclaration()
    val targetPsi = target?.findPsi() as? KtNamedDeclaration

    return if (target == null || targetPsi == null) emptyList() else workspace.sourceFiles.asSequence()
        .flatMap { it.preOrderTraversal().filterIsInstance<KtNamedDeclaration>() }
        .filter(::isImplementationCandidate)
        .mapNotNull { declaration ->
            val candidate = workspace.context[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]
                ?: return@mapNotNull null
            if (candidate.implements(target, targetPsi)) declaration.nameIdentifier?.let(::location) else null
        }
        .distinctBy { Triple(it.uri, it.range.start.line, it.range.start.character) }
        .sortedWith(compareBy({ it.uri }, { it.range.start.line }, { it.range.start.character }))
        .toList()
}

private fun targetDescriptor(file: CompiledFile, cursor: Int): DeclarationDescriptor? =
    file.referenceExpressionAtPoint(cursor)?.second
        ?: file.elementAtPoint(cursor)
            ?.findParent<KtNamedDeclaration>()
            ?.let { file.compile[BindingContext.DECLARATION_TO_DESCRIPTOR, it] }

private fun isImplementationCandidate(declaration: KtNamedDeclaration): Boolean = when (declaration) {
    is KtClassOrObject -> declaration !is KtClass || !declaration.isInterface()
    is KtNamedFunction -> !declaration.isLocal
    is KtProperty -> !declaration.isLocal
    else -> false
}

private fun DeclarationDescriptor.implements(
    target: DeclarationDescriptor,
    targetPsi: KtNamedDeclaration,
): Boolean = when {
    this.findPsi() == targetPsi -> false
    this is ClassDescriptor && target is ClassDescriptor -> transitivelyInherits(targetPsi)
    this is CallableMemberDescriptor && target is CallableMemberDescriptor -> transitivelyOverrides(targetPsi)
    else -> false
}

private fun ClassDescriptor.transitivelyInherits(targetPsi: KtNamedDeclaration): Boolean {
    val visited = Collections.newSetFromMap(IdentityHashMap<ClassDescriptor, Boolean>())
    val pending = ArrayDeque<ClassDescriptor>()
    typeConstructor.supertypes.mapNotNullTo(pending) { it.constructor.declarationDescriptor as? ClassDescriptor }

    while (pending.isNotEmpty()) {
        val candidate = pending.removeFirst()
        if (!visited.add(candidate)) continue
        if (candidate.findPsi() == targetPsi) return true
        candidate.typeConstructor.supertypes
            .mapNotNullTo(pending) { it.constructor.declarationDescriptor as? ClassDescriptor }
    }
    return false
}

private fun CallableMemberDescriptor.transitivelyOverrides(targetPsi: KtNamedDeclaration): Boolean {
    val visited = Collections.newSetFromMap(IdentityHashMap<CallableMemberDescriptor, Boolean>())
    val pending = ArrayDeque<CallableMemberDescriptor>()
    overriddenDescriptors.forEach(pending::addLast)

    while (pending.isNotEmpty()) {
        val candidate = pending.removeFirst()
        if (!visited.add(candidate)) continue
        if (candidate.findPsi() == targetPsi) return true
        candidate.overriddenDescriptors.forEach(pending::addLast)
    }
    return false
}

private fun DeclarationDescriptor.sourceDeclaration(): DeclarationDescriptor {
    val callable = this as? CallableMemberDescriptor
    return when {
        this is ConstructorDescriptor -> constructedClass
        callable == null || callable.findPsi() is KtNamedDeclaration -> this
        else -> callable.sourceOverride() ?: this
    }
}

private fun CallableMemberDescriptor.sourceOverride(): CallableMemberDescriptor? {
    val visited = Collections.newSetFromMap(IdentityHashMap<CallableMemberDescriptor, Boolean>())
    val pending = ArrayDeque<CallableMemberDescriptor>()
    var result: CallableMemberDescriptor? = null
    overriddenDescriptors.forEach(pending::addLast)

    while (pending.isNotEmpty() && result == null) {
        val candidate = pending.removeFirst()
        if (!visited.add(candidate)) continue
        if (candidate.findPsi() is KtNamedDeclaration) {
            result = candidate
        } else {
            candidate.overriddenDescriptors.forEach(pending::addLast)
        }
    }
    return result
}
