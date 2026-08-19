package org.javacs.kt.classpath

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.jetbrains.exposed.sql.Database
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class PersistentProjectModelCacheTest {
    private class Resolver(
        override val cacheIdentity: String,
        var fingerprint: String,
        var model: ResolvedClassPath
    ) : ClassPathResolver {
        override val resolverType = "Hermetic"
        override val freshnessFingerprint get() = fingerprint
        override val classpath: Set<ClassPathEntry> get() = error("resolve() should be used")
        val calls = AtomicInteger()
        var failure: RuntimeException? = null
        override fun resolve(): ResolvedClassPath {
            calls.incrementAndGet()
            failure?.let { throw it }
            return model
        }
    }

    @Test fun `restart loads SQLite without resolver and preserves source associations`() {
        val directory = Files.createTempDirectory("Workspace-cache")
        val db = Database.connect("jdbc:sqlite:${directory.resolve("models.db")}")
        val compiled = Files.createTempFile("SharedLibrary", ".jar")
        val source = Files.createTempFile("SharedLibrary", "-sources.jar")
        val script = Files.createTempFile("ProjectA", "-buildscript.jar")
        val model = ResolvedClassPath(setOf(ClassPathEntry(compiled, source)), setOf(script))

        val cold = Resolver("gradle:ProjectA", "fresh", model)
        assertThat(CachedClassPathResolver(cold, db).resolve(), equalTo(model))
        assertThat(cold.calls.get(), equalTo(1))

        ClassPathInstrumentation.reset()
        val warm = Resolver("gradle:ProjectA", "fresh", errorModel())
        val loaded = CachedClassPathResolver(warm, db).resolve()
        val counters = ClassPathInstrumentation.snapshot()
        assertThat(warm.calls.get(), equalTo(0))
        assertThat(counters.underlyingResolutions, equalTo(0))
        assertThat(counters.persistentCacheHits, equalTo(1))
        assertThat(loaded, equalTo(model))
        assertThat(loaded.classpath.single().sourceJar, equalTo(source))
    }

    @Test fun `changing one project invalidates only that project`() {
        val directory = Files.createTempDirectory("Workspace-cache")
        val db = Database.connect("jdbc:sqlite:${directory.resolve("models.db")}")
        val projectA = Resolver("gradle:ProjectA", "1", model("ProjectA"))
        val projectB = Resolver("gradle:ProjectB", "1", model("ProjectB"))
        CachedClassPathResolver(projectA, db).resolve()
        CachedClassPathResolver(projectB, db).resolve()

        val changedA = Resolver("gradle:ProjectA", "2", model("ProjectA-new"))
        val unchangedB = Resolver("gradle:ProjectB", "1", errorModel())
        CachedClassPathResolver(changedA, db).resolve()
        CachedClassPathResolver(unchangedB, db).resolve()

        assertThat(changedA.calls.get(), equalTo(1))
        assertThat(unchangedB.calls.get(), equalTo(0))
    }

    @Test fun `failed refresh preserves valid project data`() {
        val directory = Files.createTempDirectory("Workspace-cache")
        val db = Database.connect("jdbc:sqlite:${directory.resolve("models.db")}")
        val valid = model("SharedLibrary")
        CachedClassPathResolver(Resolver("gradle:ProjectA", "1", valid), db).resolve()

        val failing = Resolver("gradle:ProjectA", "2", errorModel()).apply {
            failure = IllegalStateException("Gradle failed")
        }
        assertThat(CachedClassPathResolver(failing, db).resolve(), equalTo(valid))

        val restart = Resolver("gradle:ProjectA", "1", errorModel())
        assertThat(CachedClassPathResolver(restart, db).resolve(), equalTo(valid))
        assertThat(restart.calls.get(), equalTo(0))
    }

    private fun model(name: String): ResolvedClassPath {
        val compiled = Files.createTempFile(name, ".jar")
        val source = Files.createTempFile(name, "-sources.jar")
        return ResolvedClassPath(setOf(ClassPathEntry(compiled, source)))
    }

    private fun errorModel(): ResolvedClassPath = ResolvedClassPath(
        setOf(ClassPathEntry(Path.of("resolver-must-not-be-called.jar"), null))
    )
}
