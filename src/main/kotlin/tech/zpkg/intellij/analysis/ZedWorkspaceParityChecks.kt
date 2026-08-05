package tech.zpkg.intellij.analysis

import tech.zpkg.intellij.model.ZedActionKind
import tech.zpkg.intellij.model.ZedDiagnostic
import tech.zpkg.intellij.model.ZedPackageSnapshot
import tech.zpkg.intellij.model.ZedRecommendedAction
import tech.zpkg.intellij.model.ZedSeverity
import java.nio.file.Files
import java.nio.file.Path

internal object ZedWorkspaceParityChecks {
    fun diagnostics(workspaceRoot: Path, packages: List<ZedPackageSnapshot>): List<ZedDiagnostic> {
        val roots = linkedSetOf(workspaceRoot.toAbsolutePath().normalize())
        packages.mapTo(roots) { it.root.toAbsolutePath().normalize() }
        return roots.mapNotNull { root ->
            val staging = root.resolve(".zpkg-staging")
            if (!Files.isDirectory(staging) || !hasEntries(staging)) return@mapNotNull null
            val hasLock = Files.isRegularFile(root.resolve(".zpkg.lock"))
            val hasManifest = Files.isRegularFile(root.resolve(".zpkg.toml"))
            val command = buildList {
                add("zed"); add("install")
                if (hasLock) { add("--frozen"); if (!hasManifest) add("--do-not-write-new-manifest") }
            }
            ZedDiagnostic(
                code = "ZED060",
                severity = ZedSeverity.ERROR,
                summary = "Interrupted Zed transaction needs recovery",
                details = ".zpkg-staging contains transaction state. Recovery must run before new package work.",
                packageRoot = root,
                sourceFile = staging,
                actions = listOf(
                    ZedRecommendedAction(
                        id = "zed.recover",
                        title = "Run lifecycle recovery",
                        description = "Run a Zed lifecycle command; recovery is performed before new work.",
                        kind = ZedActionKind.RUN_COMMAND,
                        workingDirectory = root,
                        command = command,
                        mutatesProject = true,
                        mayUseNetwork = true,
                    ),
                ),
            )
        }
    }

    private fun hasEntries(path: Path): Boolean = try {
        Files.newDirectoryStream(path).use { entries -> entries.iterator().hasNext() }
    } catch (_: Exception) {
        false
    }
}
