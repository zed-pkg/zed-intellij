package tech.zpkg.intellij.analysis

import tech.zpkg.intellij.model.ZedActionKind
import tech.zpkg.intellij.model.ZedDependency
import tech.zpkg.intellij.model.ZedDiagnostic
import tech.zpkg.intellij.model.ZedLockedPackage
import tech.zpkg.intellij.model.ZedPackageIdentity
import tech.zpkg.intellij.model.ZedPackageSnapshot
import tech.zpkg.intellij.model.ZedRecommendedAction
import tech.zpkg.intellij.model.ZedSeverity
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class ZedProjectAnalyzer(
    private val maxDepth: Int = 7,
) {
    private val coordinatePattern = Regex("^[a-z0-9][a-z0-9._-]*/[a-z0-9][a-z0-9._-]*$")
    private val segmentPattern = Regex("^[a-z0-9][a-z0-9._-]*$")
    private val semverPattern = Regex("^\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?$")
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")
    private val excludedDirectories = setOf(
        ".git", ".idea", ".gradle", ".zed", "build", "out", "target",
        "node_modules", "zed_modules", "vendor", ".vendor", "dist",
    )

    fun analyze(workspaceRoot: Path): AnalysisResult {
        val normalizedRoot = workspaceRoot.toAbsolutePath().normalize()
        val manifests = discoverManifests(normalizedRoot)
        val packages = manifests.map(::analyzePackage)
        val workspaceDiagnostics = if (manifests.isEmpty()) {
            listOf(
                ZedDiagnostic(
                    code = "ZED001",
                    severity = ZedSeverity.INFO,
                    summary = "No Zed package manifest found",
                    details = "The workspace does not contain a .zpkg.toml within the configured scan depth.",
                    packageRoot = normalizedRoot,
                    actions = listOf(
                        commandAction(
                            id = "zed.init",
                            title = "Initialize a Zed package",
                            description = "Run 'zed init' in the workspace root.",
                            root = normalizedRoot,
                            command = listOf("zed", "init"),
                            mutates = true,
                            network = false,
                        ),
                    ),
                ),
            )
        } else {
            emptyList()
        }
        return AnalysisResult(packages, workspaceDiagnostics)
    }

    private fun discoverManifests(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        val found = mutableListOf<Path>()
        Files.walkFileTree(root, emptySet(), maxDepth, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root && dir.fileName?.toString() in excludedDirectories) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file.fileName?.toString() == ".zpkg.toml") found.add(file)
                return FileVisitResult.CONTINUE
            }
        })
        return found.sortedBy { root.relativize(it).toString() }
    }

    private fun analyzePackage(manifest: Path): ZedPackageSnapshot {
        val root = manifest.parent
        val lock = root.resolve(".zpkg.lock")
        val diagnostics = mutableListOf<ZedDiagnostic>()

        val manifestText = readText(manifest, diagnostics, "ZED010", "Unable to read Zed manifest")
        val manifestScan = ZedTomlScanner.scan(manifestText.orEmpty())
        manifestScan.structuralErrors.forEach { (line, error) ->
            diagnostics += diagnostic(
                code = "ZED011",
                severity = ZedSeverity.ERROR,
                summary = "Malformed Zed manifest",
                details = error,
                root = root,
                file = manifest,
                line = line,
                action = openFileAction(manifest, root),
            )
        }

        val packageValues = manifestScan.assignments
            .filter { it.section == "package" && !it.arrayTable }
            .associateBy { it.key }
        val identity = ZedPackageIdentity(
            org = packageValues["org"]?.let { ZedTomlScanner.stringValue(it.rawValue) },
            name = packageValues["name"]?.let { ZedTomlScanner.stringValue(it.rawValue) },
            version = packageValues["version"]?.let { ZedTomlScanner.stringValue(it.rawValue) },
        )

        validateIdentity(identity, packageValues, root, manifest, diagnostics)

        val dependencies = manifestScan.assignments
            .filter { it.section == "dependencies" && !it.arrayTable }
            .map { assignment ->
                ZedDependency(
                    coordinate = assignment.key,
                    requirement = ZedTomlScanner.stringValue(assignment.rawValue) ?: assignment.rawValue,
                    line = assignment.line,
                )
            }
        validateDependencies(dependencies, root, manifest, diagnostics)

        var lockVersion: Long? = null
        var lockedPackages = emptyList<ZedLockedPackage>()
        if (!Files.exists(lock)) {
            diagnostics += diagnostic(
                code = "ZED030",
                severity = if (dependencies.isEmpty()) ZedSeverity.INFO else ZedSeverity.WARNING,
                summary = "Zed lockfile is missing",
                details = "Run Zed install to create a deterministic .zpkg.lock for this package.",
                root = root,
                file = manifest,
                action = commandAction(
                    id = "zed.install",
                    title = "Resolve and install dependencies",
                    description = "Run 'zed install' to create or refresh the lockfile and materialized dependency tree.",
                    root = root,
                    command = listOf("zed", "install"),
                    mutates = true,
                    network = true,
                ),
            )
        } else {
            val lockText = readText(lock, diagnostics, "ZED031", "Unable to read Zed lockfile")
            val parsedLock = parseLockfile(lockText.orEmpty(), root, lock, diagnostics)
            lockVersion = parsedLock.first
            lockedPackages = parsedLock.second
            validateLockedCoverage(dependencies, lockedPackages, root, lock, diagnostics)
        }

        val zedModules = root.resolve("zed_modules")
        val installed = Files.isDirectory(zedModules)
        if (dependencies.isNotEmpty() && !installed) {
            diagnostics += diagnostic(
                code = "ZED040",
                severity = ZedSeverity.WARNING,
                summary = "Dependencies are not materialized",
                details = "The manifest declares ${dependencies.size} direct dependency/dependencies but zed_modules is absent.",
                root = root,
                file = manifest,
                action = commandAction(
                    id = if (Files.exists(lock)) "zed.install.frozen" else "zed.install",
                    title = if (Files.exists(lock)) "Restore locked dependencies" else "Install dependencies",
                    description = if (Files.exists(lock)) {
                        "Run 'zed install --frozen' so the installed tree exactly matches .zpkg.lock."
                    } else {
                        "Run 'zed install' to resolve and materialize dependencies."
                    },
                    root = root,
                    command = if (Files.exists(lock)) listOf("zed", "install", "--frozen") else listOf("zed", "install"),
                    mutates = true,
                    network = true,
                ),
            )
        }

        return ZedPackageSnapshot(
            root = root,
            manifestPath = manifest,
            lockPath = lock,
            identity = identity,
            dependencies = dependencies,
            lockedPackages = lockedPackages,
            lockVersion = lockVersion,
            zedModulesPresent = installed,
            diagnostics = diagnostics,
        )
    }

    private fun validateIdentity(
        identity: ZedPackageIdentity,
        values: Map<String, ZedTomlScanner.Assignment>,
        root: Path,
        manifest: Path,
        diagnostics: MutableList<ZedDiagnostic>,
    ) {
        fun missing(field: String) {
            diagnostics += diagnostic(
                code = "ZED020",
                severity = ZedSeverity.ERROR,
                summary = "Package '$field' is missing",
                details = "Add a quoted '$field' value under [package].",
                root = root,
                file = manifest,
                action = openFileAction(manifest, root),
            )
        }

        if (identity.org.isNullOrBlank()) missing("org")
        else if (!segmentPattern.matches(identity.org)) {
            val line = values["org"]?.line
            diagnostics += diagnostic("ZED021", ZedSeverity.ERROR, "Package org is invalid", "Use lowercase letters, digits, '.', '_' or '-'.", root, manifest, line, openFileAction(manifest, root))
        }
        if (identity.name.isNullOrBlank()) missing("name")
        else if (!segmentPattern.matches(identity.name)) {
            val line = values["name"]?.line
            diagnostics += diagnostic("ZED022", ZedSeverity.ERROR, "Package name is invalid", "Use lowercase letters, digits, '.', '_' or '-'.", root, manifest, line, openFileAction(manifest, root))
        }
        if (identity.version.isNullOrBlank()) missing("version")
        else if (!semverPattern.matches(identity.version)) {
            val line = values["version"]?.line
            diagnostics += diagnostic("ZED023", ZedSeverity.WARNING, "Package version is not SemVer", "Use a version such as '1.2.3' or '1.2.3-rc.1'.", root, manifest, line, openFileAction(manifest, root))
        }
    }

    private fun validateDependencies(
        dependencies: List<ZedDependency>,
        root: Path,
        manifest: Path,
        diagnostics: MutableList<ZedDiagnostic>,
    ) {
        val seen = mutableSetOf<String>()
        dependencies.forEach { dependency ->
            if (!coordinatePattern.matches(dependency.coordinate)) {
                diagnostics += diagnostic("ZED024", ZedSeverity.ERROR, "Dependency coordinate is invalid", "'${dependency.coordinate}' must use the form 'org/name'.", root, manifest, dependency.line, openFileAction(manifest, root))
            }
            if (dependency.requirement.isBlank()) {
                diagnostics += diagnostic("ZED025", ZedSeverity.ERROR, "Dependency requirement is empty", "'${dependency.coordinate}' needs a version requirement.", root, manifest, dependency.line, openFileAction(manifest, root))
            }
            if (!seen.add(dependency.coordinate)) {
                diagnostics += diagnostic("ZED026", ZedSeverity.ERROR, "Dependency is declared more than once", "'${dependency.coordinate}' appears more than once in [dependencies].", root, manifest, dependency.line, openFileAction(manifest, root))
            }
        }
    }

    private fun parseLockfile(
        text: String,
        root: Path,
        lock: Path,
        diagnostics: MutableList<ZedDiagnostic>,
    ): Pair<Long?, List<ZedLockedPackage>> {
        val scan = ZedTomlScanner.scan(text)
        scan.structuralErrors.forEach { (line, error) ->
            diagnostics += diagnostic("ZED032", ZedSeverity.ERROR, "Malformed Zed lockfile", error, root, lock, line, openFileAction(lock, root))
        }

        val versionAssignment = scan.assignments.firstOrNull { it.section.isEmpty() && it.key == "version" }
        val version = versionAssignment?.let { ZedTomlScanner.longValue(it.rawValue) }
        when {
            version == null -> diagnostics += diagnostic("ZED033", ZedSeverity.ERROR, "Lockfile version is missing", "The lockfile must start with an integer 'version' field.", root, lock, versionAssignment?.line, openFileAction(lock, root))
            version > 1 -> diagnostics += diagnostic("ZED034", ZedSeverity.ERROR, "Lockfile version is unsupported", "This plugin understands lockfile version 1, but found version $version.", root, lock, versionAssignment.line, openFileAction(lock, root))
        }

        val packageAssignments = scan.assignments.filter { it.section == "package" && it.arrayTable }
        val records = packageAssignments
            .groupBy { it.tableInstance }
            .toSortedMap()
            .values
            .map { assignments -> assignments.associateBy { it.key } }

        val locked = records.mapNotNull { record ->
            val org = record["org"]?.let { ZedTomlScanner.stringValue(it.rawValue) }
            val name = record["name"]?.let { ZedTomlScanner.stringValue(it.rawValue) }
            val coordinate = if (!org.isNullOrBlank() && !name.isNullOrBlank()) "$org/$name" else null
            val line = record.values.firstOrNull()?.line ?: 1
            val packageVersion = record["version"]?.let { ZedTomlScanner.stringValue(it.rawValue) }
            val sha = record["sha256"]?.let { ZedTomlScanner.stringValue(it.rawValue) }
            if (coordinate == null) {
                diagnostics += diagnostic("ZED035", ZedSeverity.ERROR, "Locked package identity is incomplete", "Every [[package]] record needs quoted 'org' and 'name' values.", root, lock, line, openFileAction(lock, root))
                null
            } else {
                if (packageVersion.isNullOrBlank()) {
                    diagnostics += diagnostic("ZED036", ZedSeverity.ERROR, "Locked package version is missing", "$coordinate has no exact locked version.", root, lock, line, openFileAction(lock, root))
                }
                if (sha == null || !sha256Pattern.matches(sha)) {
                    diagnostics += diagnostic("ZED037", ZedSeverity.ERROR, "Locked package digest is invalid", "$coordinate must carry a canonical lowercase 64-character SHA-256 digest.", root, lock, line, openFileAction(lock, root))
                }
                ZedLockedPackage(coordinate, packageVersion, sha, line)
            }
        }
        return version to locked
    }

    private fun validateLockedCoverage(
        dependencies: List<ZedDependency>,
        lockedPackages: List<ZedLockedPackage>,
        root: Path,
        lock: Path,
        diagnostics: MutableList<ZedDiagnostic>,
    ) {
        val locked = lockedPackages.mapTo(mutableSetOf()) { it.coordinate }
        dependencies.filterNot { it.coordinate in locked }.forEach { dependency ->
            diagnostics += diagnostic(
                code = "ZED038",
                severity = ZedSeverity.WARNING,
                summary = "Direct dependency is not locked",
                details = "${dependency.coordinate} is declared in the manifest but has no [[package]] entry in .zpkg.lock.",
                root = root,
                file = lock,
                action = commandAction(
                    id = "zed.install.refresh-lock",
                    title = "Refresh the lockfile",
                    description = "Run 'zed install' to resolve all declared dependencies and rewrite .zpkg.lock.",
                    root = root,
                    command = listOf("zed", "install"),
                    mutates = true,
                    network = true,
                ),
            )
        }
    }

    private fun readText(
        path: Path,
        diagnostics: MutableList<ZedDiagnostic>,
        code: String,
        summary: String,
    ): String? = try {
        Files.readString(path)
    } catch (error: IOException) {
        diagnostics += ZedDiagnostic(
            code = code,
            severity = ZedSeverity.ERROR,
            summary = summary,
            details = error.message ?: error.javaClass.simpleName,
            packageRoot = path.parent,
            sourceFile = path,
        )
        null
    }

    private fun diagnostic(
        code: String,
        severity: ZedSeverity,
        summary: String,
        details: String,
        root: Path,
        file: Path?,
        line: Int? = null,
        action: ZedRecommendedAction? = null,
    ) = ZedDiagnostic(code, severity, summary, details, root, file, line, listOfNotNull(action))

    private fun openFileAction(file: Path, root: Path) = ZedRecommendedAction(
        id = "open.${file.fileName}",
        title = "Open ${file.fileName}",
        description = "Open the file at the relevant package root.",
        kind = ZedActionKind.OPEN_FILE,
        workingDirectory = root,
        targetFile = file,
    )

    private fun commandAction(
        id: String,
        title: String,
        description: String,
        root: Path,
        command: List<String>,
        mutates: Boolean,
        network: Boolean,
    ) = ZedRecommendedAction(
        id = id,
        title = title,
        description = description,
        kind = ZedActionKind.RUN_COMMAND,
        workingDirectory = root,
        command = command,
        mutatesProject = mutates,
        mayUseNetwork = network,
    )

    data class AnalysisResult(
        val packages: List<ZedPackageSnapshot>,
        val workspaceDiagnostics: List<ZedDiagnostic>,
    )
}
