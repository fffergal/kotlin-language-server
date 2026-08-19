package org.javacs.kt.classpath

import org.javacs.kt.LOG
import java.util.concurrent.atomic.AtomicLong

/** Lightweight counters for diagnosing and testing classpath discovery. */
object ClassPathInstrumentation {
    private val resolverCreations = AtomicLong()
    private val classpathAccesses = AtomicLong()
    private val classpathWithSourcesAccesses = AtomicLong()
    private val buildScriptClasspathAccesses = AtomicLong()
    private val underlyingResolutions = AtomicLong()
    private val persistentCacheHits = AtomicLong()

    data class Snapshot(
        val resolverCreations: Long,
        val classpathAccesses: Long,
        val classpathWithSourcesAccesses: Long,
        val buildScriptClasspathAccesses: Long,
        val underlyingResolutions: Long,
        val persistentCacheHits: Long
    )

    fun resolverCreated(roots: Collection<*>) {
        val count = resolverCreations.incrementAndGet()
        trace("resolver-created", count, roots.toString())
    }

    fun classpathAccess() = trace("classpath", classpathAccesses.incrementAndGet())

    fun classpathWithSourcesAccess() =
        trace("classpath-with-sources", classpathWithSourcesAccesses.incrementAndGet())

    fun buildScriptClasspathAccess() =
        trace("build-script-classpath", buildScriptClasspathAccesses.incrementAndGet())

    fun underlyingResolution(identity: String) =
        trace("underlying-resolution", underlyingResolutions.incrementAndGet(), identity)

    fun persistentCacheHit(identity: String) =
        trace("persistent-cache-hit", persistentCacheHits.incrementAndGet(), identity)

    fun snapshot() = Snapshot(
        resolverCreations.get(),
        classpathAccesses.get(),
        classpathWithSourcesAccesses.get(),
        buildScriptClasspathAccesses.get(),
        underlyingResolutions.get(),
        persistentCacheHits.get()
    )

    fun reset() {
        resolverCreations.set(0)
        classpathAccesses.set(0)
        classpathWithSourcesAccesses.set(0)
        buildScriptClasspathAccesses.set(0)
        underlyingResolutions.set(0)
        persistentCacheHits.set(0)
    }

    private fun trace(view: String, count: Long, detail: String = "") {
        if (java.lang.Boolean.getBoolean("kotlinLanguageServer.traceClasspath")) {
            LOG.info("Classpath trace: {} access #{} {}", view, count, detail)
        }
    }
}
