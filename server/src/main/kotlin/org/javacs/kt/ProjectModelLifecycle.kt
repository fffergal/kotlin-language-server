package org.javacs.kt

import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Coordinates LSP startup with project-model discovery. */
internal class ProjectModelLifecycle(
    private val recordRootsAction: (Collection<Path>) -> Unit,
    private val refreshAction: () -> Unit,
    private val fallbackDelayMillis: Long = 750L
) : Closeable {
    constructor(classPath: CompilerClassPath, fallbackDelayMillis: Long = 750L) : this(
        classPath::recordWorkspaceRoots,
        { classPath.refreshAsync() },
        fallbackDelayMillis
    )
    private val lock = Any()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "project-model-fallback").apply { isDaemon = true }
    }
    private var roots: Set<Path> = emptySet()
    private var initialized = false
    private var configurationReceived = false
    private var discoveryStarted = false
    private var fallback: ScheduledFuture<*>? = null

    fun recordWorkspaceRoots(newRoots: Collection<Path>) {
        val normalized = normalizeRoots(newRoots)
        synchronized(lock) {
            if (normalized == roots) return
            roots = normalized
            recordRootsAction(normalized)
            if (discoveryStarted) refreshAction()
        }
    }

    fun initialized() {
        synchronized(lock) {
            initialized = true
            if (configurationReceived) {
                startDiscoveryLocked("configuration")
            } else if (!discoveryStarted && fallback == null) {
                fallback = scheduler.schedule({
                    synchronized(lock) { startDiscoveryLocked("configuration fallback") }
                }, fallbackDelayMillis, TimeUnit.MILLISECONDS)
            }
        }
    }

    fun configurationChanged(relevant: Boolean) {
        synchronized(lock) {
            val firstConfiguration = !configurationReceived
            configurationReceived = true
            if (!initialized) return
            if (!discoveryStarted) {
                startDiscoveryLocked("configuration")
            } else if (relevant && !firstConfiguration) {
                refreshAction()
            }
        }
    }

    private fun startDiscoveryLocked(reason: String) {
        if (discoveryStarted) return
        discoveryStarted = true
        fallback?.cancel(false)
        fallback = null
        LOG.info("Starting project-model discovery after {}", reason)
        refreshAction()
    }

    override fun close() {
        fallback?.cancel(false)
        scheduler.shutdownNow()
    }
}

internal fun normalizeRoots(roots: Collection<Path>): Set<Path> {
    val normalized = roots.map { it.toAbsolutePath().normalize() }.distinct().sortedBy { it.nameCount }
    return normalized.filter { candidate -> normalized.none { parent -> parent != candidate && candidate.startsWith(parent) } }.toSet()
}
