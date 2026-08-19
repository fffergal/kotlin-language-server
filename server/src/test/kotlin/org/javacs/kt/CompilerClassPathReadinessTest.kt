package org.javacs.kt

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.sameInstance
import org.javacs.kt.classpath.ClassPathEntry
import org.javacs.kt.classpath.ClassPathResolver
import org.javacs.kt.classpath.ResolvedClassPath
import org.javacs.kt.database.DatabaseService
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class CompilerClassPathReadinessTest {
    @Test fun `concurrent refreshes share one resolution and readiness waits`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resolverCreations = AtomicInteger()
        val resolver = object : ClassPathResolver {
            override val resolverType = "ProjectA"
            override val classpath: Set<ClassPathEntry>
                get() {
                    entered.countDown()
                    release.await()
                    return setOf(ClassPathEntry(Files.createTempFile("SharedLibrary", ".jar"), null))
                }
        }
        val classPath = CompilerClassPath(
            CompilerConfiguration(), ScriptsConfiguration(), CodegenConfiguration(), DatabaseService()
        ) { _, _ ->
            resolverCreations.incrementAndGet()
            resolver
        }
        try {
            classPath.recordWorkspaceRoots(emptyList())
            val first = classPath.refreshAsync()
            assertThat(entered.await(5, TimeUnit.SECONDS), equalTo(true))
            val second = classPath.refreshAsync()
            assertThat(second, sameInstance(first))

            val readinessReturned = CountDownLatch(1)
            thread { classPath.awaitReady(); readinessReturned.countDown() }
            assertThat(readinessReturned.await(50, TimeUnit.MILLISECONDS), equalTo(false))
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            assertThat(readinessReturned.await(5, TimeUnit.SECONDS), equalTo(true))
            assertThat(resolverCreations.get(), equalTo(1))
        } finally {
            release.countDown()
            classPath.close()
        }
    }

    @Test fun `coherent model does not access independent classpath views`() {
        val resolves = AtomicInteger()
        val compiled = Files.createTempFile("SharedLibrary", ".jar")
        val source = Files.createTempFile("SharedLibrary", "-sources.jar")
        val buildScript = Files.createTempFile("ProjectA-buildscript", ".jar")
        val resolver = object : ClassPathResolver {
            override val resolverType = "ProjectA"
            override val classpath: Set<ClassPathEntry> get() = error("independent classpath access")
            override val classpathWithSources: Set<ClassPathEntry> get() = error("independent source access")
            override val buildScriptClasspath: Set<java.nio.file.Path> get() = error("independent build-script access")
            override fun resolve() = ResolvedClassPath(
                setOf(ClassPathEntry(compiled, source)), setOf(buildScript)
            ).also { resolves.incrementAndGet() }
        }
        val classPath = CompilerClassPath(
            CompilerConfiguration(), ScriptsConfiguration(), CodegenConfiguration(), DatabaseService()
        ) { _, _ -> resolver }
        try {
            classPath.recordWorkspaceRoots(emptyList())
            classPath.refreshAsync().get(5, TimeUnit.SECONDS)
            assertThat(resolves.get(), equalTo(1))
            assertThat(classPath.classPath, equalTo(setOf(ClassPathEntry(compiled, source))))
        } finally {
            classPath.close()
        }
    }

    @Test fun `failed refresh preserves last valid model`() {
        val compiled = Files.createTempFile("SharedLibrary", ".jar")
        val source = Files.createTempFile("SharedLibrary", "-sources.jar")
        val attempts = AtomicInteger()
        val classPath = CompilerClassPath(
            CompilerConfiguration(), ScriptsConfiguration(), CodegenConfiguration(), DatabaseService()
        ) { _, _ ->
            object : ClassPathResolver {
                override val resolverType = "ProjectB"
                override val classpath: Set<ClassPathEntry> get() = error("unused")
                override fun resolve(): ResolvedClassPath {
                    if (attempts.incrementAndGet() > 1) error("resolution failed")
                    return ResolvedClassPath(setOf(ClassPathEntry(compiled, source)))
                }
            }
        }
        try {
            classPath.recordWorkspaceRoots(emptyList())
            classPath.refreshAsync().get(5, TimeUnit.SECONDS)
            val valid = classPath.classPath.toSet()
            classPath.refreshAsync().get(5, TimeUnit.SECONDS)
            assertThat(classPath.classPath.toSet(), equalTo(valid))
        } finally {
            classPath.close()
        }
    }

    @Test fun `dependency removals recreate compiler while additions reuse it`() {
        val firstJar = Files.createTempFile("First", ".jar")
        val secondJar = Files.createTempFile("Second", ".jar")
        var model = ResolvedClassPath(setOf(ClassPathEntry(firstJar, null)))
        val classPath = CompilerClassPath(
            CompilerConfiguration(), ScriptsConfiguration(), CodegenConfiguration(), DatabaseService()
        ) { _, _ -> object : ClassPathResolver {
            override val resolverType = "Mutable"
            override val classpath get() = model.classpath
            override fun resolve() = model
        } }
        try {
            classPath.recordWorkspaceRoots(emptyList())
            classPath.refreshAsync().get(5, TimeUnit.SECONDS)
            val initialCompiler = classPath.compiler

            model = ResolvedClassPath(setOf(ClassPathEntry(firstJar, null), ClassPathEntry(secondJar, null)))
            classPath.refreshAsync().get(5, TimeUnit.SECONDS)
            assertThat(classPath.compiler, sameInstance(initialCompiler))

            model = ResolvedClassPath(setOf(ClassPathEntry(secondJar, null)))
            classPath.refreshAsync().get(5, TimeUnit.SECONDS)
            assertThat(classPath.compiler === initialCompiler, equalTo(false))
        } finally {
            classPath.close()
        }
    }

    @Test fun `removing recorded Java source recreates compiler`() {
        val compiled = Files.createTempFile("Library", ".jar")
        val javaSource = Files.createTempFile("Example", ".java")
        val resolver = object : ClassPathResolver {
            override val resolverType = "Stable"
            override val classpath = setOf(ClassPathEntry(compiled, null))
        }
        val classPath = CompilerClassPath(
            CompilerConfiguration(), ScriptsConfiguration(), CodegenConfiguration(), DatabaseService()
        ) { _, _ -> resolver }
        try {
            classPath.recordJavaSourcePaths(setOf(javaSource))
            classPath.recordWorkspaceRoots(emptyList())
            classPath.refreshAsync().get(5, TimeUnit.SECONDS)
            val initialCompiler = classPath.compiler

            classPath.recordJavaSourcePaths(emptySet())
            classPath.refreshAsync().get(5, TimeUnit.SECONDS)
            assertThat(classPath.compiler === initialCompiler, equalTo(false))
        } finally {
            classPath.close()
        }
    }
}
