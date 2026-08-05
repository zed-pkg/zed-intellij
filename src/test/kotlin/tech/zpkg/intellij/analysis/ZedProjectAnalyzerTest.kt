package tech.zpkg.intellij.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.zpkg.intellij.model.ZedSeverity

class ZedProjectAnalyzerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `discovers nested package and validates locked dependency`() {
        val root = temporaryFolder.newFolder("workspace").toPath()
        val packageRoot = root.resolve("packages/example")
        packageRoot.toFile().mkdirs()
        packageRoot.resolve(".zpkg.toml").toFile().writeText(
            """
            [package]
            org = "acme"
            name = "example"
            version = "1.2.3"

            [dependencies]
            "zed-pkg/zed-interfaces" = "^0.1.0"
            """.trimIndent(),
        )
        packageRoot.resolve(".zpkg.lock").toFile().writeText(
            """
            version = 1

            [[package]]
            org = "zed-pkg"
            name = "zed-interfaces"
            version = "0.1.0"
            sha256 = "${"a".repeat(64)}"
            size = 1
            format = "tar-zstd"
            vcs_tag = "v0.1.0"
            vcs_commit = "abcdef1"
            source = "https://registry.zpkg.tech"
            """.trimIndent(),
        )
        packageRoot.resolve("zed_modules").toFile().mkdirs()

        val result = ZedProjectAnalyzer().analyze(root)

        assertEquals(1, result.packages.size)
        val pkg = result.packages.single()
        assertEquals("acme/example", pkg.identity.displayName)
        assertEquals(1, pkg.dependencies.size)
        assertEquals(1, pkg.lockedPackages.size)
        assertTrue(pkg.diagnostics.none { it.severity == ZedSeverity.ERROR })
    }

    @Test
    fun `reports absent lock and materialized tree`() {
        val root = temporaryFolder.newFolder("workspace").toPath()
        root.resolve(".zpkg.toml").toFile().writeText(
            """
            [package]
            org = "acme"
            name = "example"
            version = "1.0.0"

            [dependencies]
            "zed-pkg/zed-interfaces" = "^0.1.0"
            """.trimIndent(),
        )

        val pkg = ZedProjectAnalyzer().analyze(root).packages.single()
        val codes = pkg.diagnostics.map { it.code }.toSet()

        assertTrue("ZED030" in codes)
        assertTrue("ZED040" in codes)
        assertTrue(pkg.diagnostics.flatMap { it.actions }.all { action ->
            !action.mutatesProject || action.command.isNotEmpty()
        })
    }

    @Test
    fun `does not descend into excluded directories`() {
        val root = temporaryFolder.newFolder("workspace").toPath()
        val ignored = root.resolve("node_modules/example")
        ignored.toFile().mkdirs()
        ignored.resolve(".zpkg.toml").toFile().writeText("[package]\norg='x'\nname='y'\nversion='1.0.0'\n")

        val result = ZedProjectAnalyzer().analyze(root)

        assertTrue(result.packages.isEmpty())
        assertEquals("ZED001", result.workspaceDiagnostics.single().code)
    }
}
