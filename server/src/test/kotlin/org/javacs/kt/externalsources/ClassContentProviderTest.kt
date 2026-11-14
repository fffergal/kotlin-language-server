package org.javacs.kt.externalsources

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.javacs.kt.CodegenConfiguration
import org.javacs.kt.CompilerClassPath
import org.javacs.kt.CompilerConfiguration
import org.javacs.kt.ExternalSourcesConfiguration
import org.javacs.kt.ScriptsConfiguration
import org.javacs.kt.database.DatabaseService
import org.javacs.kt.util.TemporaryDirectory
import org.junit.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class ClassContentProviderTest {
    @Test fun `missing source entry falls back to decompiled class`() {
        val compiledJar = jarWith("example/Library.class", byteArrayOf(1))
        val emptySourceJar = jarWith("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".toByteArray())
        val decompiled = Files.createTempFile("Library", ".java").apply {
            Files.writeString(this, "package example; public class Library {}")
        }
        val classPath = CompilerClassPath(
            CompilerConfiguration(), ScriptsConfiguration(), CodegenConfiguration(), DatabaseService()
        )
        TemporaryDirectory().use { temporaryDirectory ->
            val provider = ClassContentProvider(
                ExternalSourcesConfiguration(),
                classPath,
                temporaryDirectory,
                object : SourceArchiveProvider {
                    override fun fetchSourceArchive(compiledArchive: Path): Path = emptySourceJar
                },
                object : Decompiler {
                    override fun decompileClass(compiledClass: Path): Path = decompiled
                    override fun decompileJar(compiledJar: Path): Path = error("unused")
                },
            )
            val uri = KlsURI(URI("kls:${compiledJar.toUri()}!/example/Library.class"))

            val (resolved, content) = provider.contentOf(uri)

            assertThat(resolved.fileExtension, equalTo("java"))
            assertThat(content, containsString("public class Library"))
        }
        classPath.close()
    }

    private fun jarWith(name: String, content: ByteArray): Path =
        Files.createTempFile("archive", ".jar").also { path ->
            JarOutputStream(Files.newOutputStream(path)).use { output ->
                output.putNextEntry(JarEntry(name))
                output.write(content)
                output.closeEntry()
            }
        }
}
