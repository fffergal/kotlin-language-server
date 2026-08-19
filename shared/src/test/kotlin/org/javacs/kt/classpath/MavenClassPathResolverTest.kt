package org.javacs.kt.classpath

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test
import java.nio.file.Files

class MavenClassPathResolverTest {
    @Test fun `local parent POM participates in freshness fingerprint`() {
        val root = Files.createTempDirectory("MavenParent")
        val parent = root.resolve("pom.xml")
        Files.writeString(parent, "<project><version>1</version></project>")
        val childDirectory = Files.createDirectories(root.resolve("child"))
        val child = childDirectory.resolve("pom.xml")
        Files.writeString(child, "<project><parent><relativePath>../pom.xml</relativePath></parent></project>")
        val resolver = MavenClassPathResolver.maybeCreate(child)!!
        val before = resolver.freshnessFingerprint
        val originalTime = Files.getLastModifiedTime(parent)

        Files.writeString(parent, "<project><version>2</version></project>")
        Files.setLastModifiedTime(parent, originalTime)

        assertThat(resolver.freshnessFingerprint == before, equalTo(false))
    }
}
