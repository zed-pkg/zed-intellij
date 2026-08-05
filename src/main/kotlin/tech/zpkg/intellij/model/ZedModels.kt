package tech.zpkg.intellij.model

import java.nio.file.Path
import java.time.Instant

enum class ZedSeverity(val rank: Int) {
    ERROR(3),
    WARNING(2),
    INFO(1),
}

enum class ZedActionKind {
    RUN_COMMAND,
    OPEN_FILE,
    OPEN_CLI_DOCUMENTATION,
}

data class ZedRecommendedAction(
    val id: String,
    val title: String,
    val description: String,
    val kind: ZedActionKind,
    val workingDirectory: Path? = null,
    val command: List<String> = emptyList(),
    val targetFile: Path? = null,
    val mutatesProject: Boolean = false,
    val mayUseNetwork: Boolean = false,
)

data class ZedDiagnostic(
    val code: String,
    val severity: ZedSeverity,
    val summary: String,
    val details: String,
    val packageRoot: Path?,
    val sourceFile: Path? = null,
    val sourceLine: Int? = null,
    val actions: List<ZedRecommendedAction> = emptyList(),
)

data class ZedPackageIdentity(
    val org: String?,
    val name: String?,
    val version: String?,
) {
    val displayName: String
        get() = when {
            !org.isNullOrBlank() && !name.isNullOrBlank() -> "$org/$name"
            !name.isNullOrBlank() -> name
            else -> "Unidentified package"
        }
}

data class ZedDependency(
    val coordinate: String,
    val requirement: String,
    val line: Int,
)

data class ZedLockedPackage(
    val coordinate: String,
    val version: String?,
    val sha256: String?,
    val line: Int,
)

data class ZedPackageSnapshot(
    val root: Path,
    val manifestPath: Path,
    val lockPath: Path,
    val identity: ZedPackageIdentity,
    val dependencies: List<ZedDependency>,
    val lockedPackages: List<ZedLockedPackage>,
    val lockVersion: Long?,
    val zedModulesPresent: Boolean,
    val diagnostics: List<ZedDiagnostic>,
)

data class ZedCliSnapshot(
    val available: Boolean,
    val versionText: String? = null,
    val failure: String? = null,
)

data class ZedWorkspaceSnapshot(
    val root: Path,
    val scannedAt: Instant,
    val packages: List<ZedPackageSnapshot>,
    val cli: ZedCliSnapshot,
    val workspaceDiagnostics: List<ZedDiagnostic>,
) {
    val diagnostics: List<ZedDiagnostic>
        get() = (workspaceDiagnostics + packages.flatMap { it.diagnostics })
            .sortedWith(compareByDescending<ZedDiagnostic> { it.severity.rank }.thenBy { it.summary })

    companion object {
        fun empty(root: Path): ZedWorkspaceSnapshot = ZedWorkspaceSnapshot(
            root = root,
            scannedAt = Instant.EPOCH,
            packages = emptyList(),
            cli = ZedCliSnapshot(available = false),
            workspaceDiagnostics = emptyList(),
        )
    }
}
