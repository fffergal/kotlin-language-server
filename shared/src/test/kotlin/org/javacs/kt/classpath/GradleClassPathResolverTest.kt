package org.javacs.kt.classpath

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.equalTo
import org.javacs.kt.util.isOSWindows
import org.junit.Test
import java.nio.file.Files

class GradleClassPathResolverTest {
    @Test fun `coherent model includes main test and build script paths in one invocation`() {
        val project = Files.createTempDirectory("GradleProject")
        val buildFile = project.resolve("build.gradle.kts")
        Files.writeString(buildFile, "")
        val mainJar = Files.createTempFile("main", ".jar")
        val testJar = Files.createTempFile("test", ".jar")
        val buildScriptJar = Files.createTempFile("buildscript", ".jar")
        val calls = project.resolve("calls.txt")
        val wrapper = project.resolve(if (isOSWindows()) "gradlew.bat" else "gradlew")
        val script = if (isOSWindows()) {
            "@echo off\r\necho call>>\"$calls\"\r\necho kotlin-lsp-gradle $mainJar\r\necho kotlin-lsp-gradle $testJar\r\necho kotlin-lsp-buildscript $buildScriptJar\r\n"
        } else {
            "#!/bin/sh\necho call >> '$calls'\necho 'kotlin-lsp-gradle $mainJar'\necho 'kotlin-lsp-gradle $testJar'\necho 'kotlin-lsp-buildscript $buildScriptJar'\n"
        }
        Files.writeString(wrapper, script)
        wrapper.toFile().setExecutable(true)

        val model = GradleClassPathResolver.maybeCreate(buildFile)!!.resolve()

        assertThat(model.classpath.map { it.compiledJar }, containsInAnyOrder(mainJar, testJar))
        assertThat(model.buildScriptClasspath, equalTo(setOf(buildScriptJar)))
        assertThat(Files.readAllLines(calls).size, equalTo(1))
    }

    @Test fun `version catalog content participates in freshness fingerprint`() {
        val project = Files.createTempDirectory("GradleFingerprint")
        val buildFile = project.resolve("build.gradle.kts")
        Files.writeString(buildFile, "")
        val gradleDirectory = Files.createDirectories(project.resolve("gradle"))
        val catalog = gradleDirectory.resolve("libs.versions.toml")
        Files.writeString(catalog, "version = \"1\"")
        val originalTime = Files.getLastModifiedTime(catalog)
        val resolver = GradleClassPathResolver.maybeCreate(buildFile)!!
        val before = resolver.freshnessFingerprint

        Files.writeString(catalog, "version = \"2\"")
        Files.setLastModifiedTime(catalog, originalTime)

        assertThat(resolver.freshnessFingerprint == before, equalTo(false))
    }
}
