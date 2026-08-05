package tech.zpkg.intellij.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZedWorkspaceParityChecksTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    @Test fun `reports interrupted transaction and confirmed recovery command`() {
        val root = temporaryFolder.newFolder("workspace").toPath()
        root.resolve(".zpkg.toml").toFile().writeText("[package]\norg = \"acme\"\nname = \"widget\"\nversion = \"1.0.0\"\n")
        root.resolve(".zpkg.lock").toFile().writeText("version = 1\n")
        val staging = root.resolve(".zpkg-staging"); staging.toFile().mkdirs(); staging.resolve("journal.json").toFile().writeText("{}")
        val diagnostic = ZedWorkspaceParityChecks.diagnostics(root, emptyList()).single()
        assertEquals("ZED060", diagnostic.code)
        val action = diagnostic.actions.single(); assertTrue(action.mutatesProject); assertEquals(listOf("zed", "install", "--frozen"), action.command)
    }
    @Test fun `ignores empty staging directory`() {
        val root = temporaryFolder.newFolder("workspace").toPath(); root.resolve(".zpkg-staging").toFile().mkdirs()
        assertTrue(ZedWorkspaceParityChecks.diagnostics(root, emptyList()).isEmpty())
    }
}
