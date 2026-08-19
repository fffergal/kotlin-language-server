package org.javacs.kt.classpath

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test
import java.nio.file.Files

class ProjectDiscoveryTest {
    @Test fun `Gradle subprojects resolve once per distinct build`() {
        val workspace = Files.createTempDirectory("Workspace")
        Files.writeString(workspace.resolve("settings.gradle.kts"), "include(\"ProjectA\")")
        Files.writeString(workspace.resolve("build.gradle.kts"), "")
        Files.createDirectories(workspace.resolve("ProjectA"))
        Files.writeString(workspace.resolve("ProjectA/build.gradle.kts"), "")

        val resolvers = workspaceResolvers(workspace).toList()
        assertThat(resolvers.count { it.resolverType == "Gradle" }, equalTo(1))
    }

    @Test fun `nested independent Gradle build is discovered separately`() {
        val workspace = Files.createTempDirectory("Workspace")
        Files.writeString(workspace.resolve("settings.gradle.kts"), "include(\"ProjectA\")")
        Files.writeString(workspace.resolve("build.gradle.kts"), "")
        val projectA = Files.createDirectories(workspace.resolve("ProjectA"))
        Files.writeString(projectA.resolve("build.gradle.kts"), "")
        val projectB = Files.createDirectories(workspace.resolve("ProjectB"))
        Files.writeString(projectB.resolve("settings.gradle.kts"), "")
        Files.writeString(projectB.resolve("build.gradle.kts"), "")

        val resolvers = workspaceResolvers(workspace).toList()
        assertThat(resolvers.count { it.resolverType == "Gradle" }, equalTo(2))
    }

    @Test fun `Gradle discovery ignores unrelated Config files`() {
        val workspace = Files.createTempDirectory("Workspace")
        Files.writeString(workspace.resolve("Config"), "package metadata")
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        Files.writeString(workspace.resolve("build.gradle.kts"), "")

        val resolvers = workspaceResolvers(workspace).toList()
        assertThat(resolvers.count { it.resolverType == "Gradle" }, equalTo(1))
    }

    @Test fun `generated Maven metadata is ignored without root gitignore`() {
        val workspace = Files.createTempDirectory("Workspace")
        val project = Files.createDirectories(workspace.resolve("Project"))
        Files.writeString(project.resolve("build.gradle.kts"), "")
        val generatedPom = Files.createDirectories(
            project.resolve("build/private/gradle/tmp/.cache/expanded/archive/META-INF/maven/org.example/artifact")
        ).resolve("pom.xml")
        Files.writeString(generatedPom, "<project/>")

        val resolvers = workspaceResolvers(workspace).toList()

        assertThat(resolvers.count { it.resolverType == "Gradle" }, equalTo(1))
        assertThat(resolvers.count { it.resolverType == "Maven" }, equalTo(0))
    }
}