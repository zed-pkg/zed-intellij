package tech.zpkg.intellij.service

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import tech.zpkg.intellij.analysis.ZedProjectAnalyzer
import tech.zpkg.intellij.analysis.ZedWorkspaceParityChecks
import tech.zpkg.intellij.model.ZedActionKind
import tech.zpkg.intellij.model.ZedDiagnostic
import tech.zpkg.intellij.model.ZedRecommendedAction
import tech.zpkg.intellij.model.ZedSeverity
import tech.zpkg.intellij.model.ZedWorkspaceSnapshot
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class ZedProjectService(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(ZedProjectService::class.java)
    private val analyzer = ZedProjectAnalyzer()
    private val cli = ZedCliService()
    private val scanRunning = AtomicBoolean(false)
    private val scanQueued = AtomicBoolean(false)

    @Volatile
    var snapshot: ZedWorkspaceSnapshot = ZedWorkspaceSnapshot.empty(workspaceRoot())
        private set

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any(::isZedStateFile)) refresh(showNotification = false)
                }
            },
        )
    }

    fun start() = refresh(showNotification = false)

    fun refresh(showNotification: Boolean) {
        if (!scanRunning.compareAndSet(false, true)) { scanQueued.set(true); return }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val root = workspaceRoot()
                val analysis = analyzer.analyze(root)
                val parityDiagnostics = ZedWorkspaceParityChecks.diagnostics(root, analysis.packages)
                val cliState = cli.probe(root)
                val cliDiagnostic = if (cliState.available) emptyList() else listOf(
                    ZedDiagnostic(
                        code = "ZED050",
                        severity = ZedSeverity.WARNING,
                        summary = "Zed CLI is unavailable or incompatible",
                        details = cliState.failure ?: "The zed-pkg executable is not available on the IDE process PATH.",
                        packageRoot = root,
                        actions = listOf(
                            ZedRecommendedAction(
                                id = "zed.cli.docs",
                                title = "Open Zed CLI installation documentation",
                                description = "Open the zed-cli repository and installation instructions.",
                                kind = ZedActionKind.OPEN_CLI_DOCUMENTATION,
                            ),
                        ),
                    ),
                )
                publish(
                    ZedWorkspaceSnapshot(
                        root = root,
                        scannedAt = Instant.now(),
                        packages = analysis.packages,
                        cli = cliState,
                        workspaceDiagnostics = analysis.workspaceDiagnostics + parityDiagnostics + cliDiagnostic,
                    ),
                    showNotification,
                )
            } catch (error: Exception) {
                logger.warn("Zed project scan failed", error)
                notify("Zed package scan failed", ZedOutputRedactor.redact(error.message ?: error.javaClass.simpleName), NotificationType.ERROR)
            } finally {
                scanRunning.set(false)
                if (scanQueued.getAndSet(false)) refresh(showNotification = false)
            }
        }
    }

    fun runAction(action: ZedRecommendedAction, completion: (Boolean, String) -> Unit) {
        val root = action.workingDirectory ?: workspaceRoot()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val output = cli.execute(action.command, root)
                val success = output.exitCode == 0 && !output.isTimeout
                val text = buildString {
                    if (output.stdout.isNotBlank()) append(output.stdout.trim())
                    if (output.stderr.isNotBlank()) { if (isNotEmpty()) append("\n\n"); append(output.stderr.trim()) }
                    if (isEmpty()) append("Command exited with status ${output.exitCode}.")
                    if (output.isTimeout) append("\nThe command timed out.")
                }
                ApplicationManager.getApplication().invokeLater {
                    completion(success, ZedOutputRedactor.redact(text))
                    if (success) refresh(showNotification = false)
                }
            } catch (error: Exception) {
                ApplicationManager.getApplication().invokeLater { completion(false, ZedOutputRedactor.redact(error.message ?: error.javaClass.simpleName)) }
            }
        }
    }

    private fun publish(next: ZedWorkspaceSnapshot, showNotification: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            snapshot = next
            project.messageBus.syncPublisher(ZedStateListener.TOPIC).stateChanged(next)
            if (showNotification) {
                val errors = next.diagnostics.count { it.severity == ZedSeverity.ERROR }
                val warnings = next.diagnostics.count { it.severity == ZedSeverity.WARNING }
                notify("Zed package state refreshed", "${next.packages.size} package(s), $errors error(s), $warnings warning(s).", if (errors > 0) NotificationType.WARNING else NotificationType.INFORMATION)
            }
        }
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("Zed Package Insights").createNotification(title, ZedOutputRedactor.redact(content), type).notify(project)
        }
    }

    private fun workspaceRoot(): Path = Path.of(project.basePath ?: System.getProperty("user.dir")).toAbsolutePath().normalize()
    private fun isZedStateFile(event: VFileEvent): Boolean {
        val name = event.file?.name ?: event.path.substringAfterLast('/')
        return name == ".zpkg.toml" || name == ".zpkg.lock" || name == "zed_modules" || name == ".zpkg-staging"
    }
    override fun dispose() = Unit
}
