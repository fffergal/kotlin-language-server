package org.javacs.kt

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class ProjectModelLifecycleTest {
    @Test fun `configuration precedes discovery`() {
        val refreshes = AtomicInteger()
        ProjectModelLifecycle({}, { refreshes.incrementAndGet() }, 5_000).use { lifecycle ->
            lifecycle.recordWorkspaceRoots(listOf(Path.of("Workspace")))
            assertThat(refreshes.get(), equalTo(0))
            lifecycle.initialized()
            assertThat(refreshes.get(), equalTo(0))
            lifecycle.configurationChanged(relevant = false)
            assertThat(refreshes.get(), equalTo(1))
        }
    }

    @Test fun `discovery falls back when configuration is absent`() {
        val refreshes = AtomicInteger()
        ProjectModelLifecycle({}, { refreshes.incrementAndGet() }, 25).use { lifecycle ->
            lifecycle.initialized()
            val deadline = System.nanoTime() + 1_000_000_000L
            while (refreshes.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
            assertThat(refreshes.get(), equalTo(1))
        }
    }

    @Test fun `relevant configuration change causes one controlled refresh`() {
        val refreshes = AtomicInteger()
        ProjectModelLifecycle({}, { refreshes.incrementAndGet() }, 5_000).use { lifecycle ->
            lifecycle.initialized()
            lifecycle.configurationChanged(relevant = true)
            lifecycle.configurationChanged(relevant = false)
            lifecycle.configurationChanged(relevant = true)
            assertThat(refreshes.get(), equalTo(2))
        }
    }

    @Test fun `duplicate and overlapping roots are normalized`() {
        val workspace = Path.of("Workspace").toAbsolutePath()
        assertThat(
            normalizeRoots(listOf(workspace, workspace, workspace.resolve("ProjectA"), workspace.resolve("ProjectB"))),
            equalTo(setOf(workspace.normalize()))
        )
    }

    @Test fun `nested root added after discovery does not refresh effective parent`() {
        val refreshes = AtomicInteger()
        val records = mutableListOf<Collection<Path>>()
        val workspace = Path.of("Workspace").toAbsolutePath().normalize()
        ProjectModelLifecycle({ records.add(it) }, { refreshes.incrementAndGet() }, 5_000).use { lifecycle ->
            lifecycle.recordWorkspaceRoots(listOf(workspace))
            lifecycle.initialized()
            lifecycle.configurationChanged(relevant = false)
            lifecycle.recordWorkspaceRoots(listOf(workspace, workspace.resolve("ProjectA")))

            assertThat(refreshes.get(), equalTo(1))
            assertThat(records, equalTo(listOf(setOf(workspace))))
        }
    }
}