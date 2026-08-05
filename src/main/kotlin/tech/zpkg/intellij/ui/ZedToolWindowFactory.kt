package tech.zpkg.intellij.ui

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBSplitter
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import tech.zpkg.intellij.model.ZedActionKind
import tech.zpkg.intellij.model.ZedDiagnostic
import tech.zpkg.intellij.model.ZedRecommendedAction
import tech.zpkg.intellij.model.ZedSeverity
import tech.zpkg.intellij.model.ZedWorkspaceSnapshot
import tech.zpkg.intellij.service.ZedProjectService
import tech.zpkg.intellij.service.ZedStateListener
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

class ZedToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ZedToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, null, false)
        toolWindow.contentManager.addContent(content)
        panel.bind()
    }
}

private class ZedToolWindowPanel(
    private val project: Project,
) {
    private val service = project.service<ZedProjectService>()
    private val summary = JBLabel()
    private val cli = JBLabel()
    private val scanned = JBLabel()
    private val details = JTextArea()
    private val tableModel = DiagnosticTableModel()
    private val table = JBTable(tableModel)
    private val refreshButton = JButton("Refresh")
    private val applyButton = JButton("Apply recommended action")

    val component: JPanel = JPanel(BorderLayout())

    init {
        val header = FormBuilder.createFormBuilder()
            .addLabeledComponent("Workspace:", JBLabel(project.basePath ?: "Unknown"))
            .addLabeledComponent("State:", summary)
            .addLabeledComponent("CLI:", cli)
            .addLabeledComponent("Scanned:", scanned)
            .panel

        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.autoCreateRowSorter = true
        table.emptyText.text = "No Zed package diagnostics"
        table.selectionModel.addListSelectionListener { updateDetails() }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) executeSelectedAction()
            }
        })

        details.isEditable = false
        details.lineWrap = true
        details.wrapStyleWord = true
        details.background = UIUtil.getPanelBackground()
        details.border = JBUI.Borders.empty(8)

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        buttons.add(refreshButton)
        buttons.add(applyButton)
        refreshButton.addActionListener { service.refresh(showNotification = true) }
        applyButton.addActionListener { executeSelectedAction() }

        val split = JBSplitter(true, 0.72f)
        split.firstComponent = JBScrollPane(table)
        split.secondComponent = JBScrollPane(details)

        component.border = JBUI.Borders.empty(8)
        component.add(header, BorderLayout.NORTH)
        component.add(split, BorderLayout.CENTER)
        component.add(buttons, BorderLayout.SOUTH)
        render(service.snapshot)
    }

    fun bind() {
        project.messageBus.connect(project).subscribe(
            ZedStateListener.TOPIC,
            ZedStateListener(::render),
        )
    }

    private fun render(snapshot: ZedWorkspaceSnapshot) {
        val errors = snapshot.diagnostics.count { it.severity == ZedSeverity.ERROR }
        val warnings = snapshot.diagnostics.count { it.severity == ZedSeverity.WARNING }
        summary.text = "${snapshot.packages.size} package(s), $errors error(s), $warnings warning(s)"
        cli.text = if (snapshot.cli.available) snapshot.cli.versionText ?: "Available" else "Unavailable"
        scanned.text = if (snapshot.scannedAt.epochSecond == 0L) {
            "Not yet"
        } else {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(snapshot.scannedAt)
        }
        tableModel.setDiagnostics(snapshot.diagnostics)
        applyButton.isEnabled = false
        details.text = if (snapshot.diagnostics.isEmpty()) {
            "No problems detected. Scans are read-only; commands run only after explicit confirmation."
        } else {
            "Select a finding to see evidence and its recommended action."
        }
    }

    private fun selectedDiagnostic(): ZedDiagnostic? {
        val viewRow = table.selectedRow
        if (viewRow < 0) return null
        return tableModel.get(table.convertRowIndexToModel(viewRow))
    }

    private fun updateDetails() {
        val diagnostic = selectedDiagnostic()
        if (diagnostic == null) {
            applyButton.isEnabled = false
            return
        }
        val action = diagnostic.actions.firstOrNull()
        details.text = buildString {
            append(diagnostic.code).append(" — ").append(diagnostic.summary).append("\n\n")
            append(diagnostic.details)
            diagnostic.sourceFile?.let { file ->
                append("\n\nSource: ").append(file)
                diagnostic.sourceLine?.let { append(':').append(it) }
            }
            action?.let {
                append("\n\nRecommended action: ").append(it.title)
                append("\n").append(it.description)
                if (it.command.isNotEmpty()) {
                    append("\nCommand: ").append(it.command.joinToString(" "))
                    append("\nWorking directory: ").append(it.workingDirectory)
                }
            }
        }
        applyButton.text = action?.title ?: "No automatic action"
        applyButton.isEnabled = action != null
    }

    private fun executeSelectedAction() {
        val action = selectedDiagnostic()?.actions?.firstOrNull() ?: return
        when (action.kind) {
            ZedActionKind.OPEN_FILE -> openFile(action)
            ZedActionKind.OPEN_CLI_DOCUMENTATION -> BrowserUtil.browse("https://github.com/zed-pkg/zed-cli")
            ZedActionKind.RUN_COMMAND -> runCommand(action)
        }
    }

    private fun openFile(action: ZedRecommendedAction) {
        val path = action.targetFile ?: return
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun runCommand(action: ZedRecommendedAction) {
        if (action.command.isEmpty()) return
        val exactCommand = action.command.joinToString(" ")
        if (action.mutatesProject) {
            val networkNotice = if (action.mayUseNetwork) "\n\nThis command may access the network." else ""
            val choice = Messages.showYesNoDialog(
                project,
                "Run the following command?\n\n$exactCommand\n\nWorking directory:\n${action.workingDirectory}$networkNotice",
                "Confirm Zed package action",
                "Run command",
                "Cancel",
                Messages.getQuestionIcon(),
            )
            if (choice != Messages.YES) return
        }

        applyButton.isEnabled = false
        applyButton.text = "Running…"
        service.runAction(action) { success, output ->
            applyButton.isEnabled = true
            applyButton.text = action.title
            details.text = output
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Zed Package Insights")
                .createNotification(
                    if (success) "Zed command completed" else "Zed command failed",
                    output.take(2_000),
                    if (success) NotificationType.INFORMATION else NotificationType.ERROR,
                )
                .notify(project)
        }
    }
}

private class DiagnosticTableModel : AbstractTableModel() {
    private val columns = arrayOf("Severity", "Package", "Finding", "Recommended action")
    private var diagnostics: List<ZedDiagnostic> = emptyList()

    fun setDiagnostics(next: List<ZedDiagnostic>) {
        diagnostics = next
        fireTableDataChanged()
    }

    fun get(row: Int): ZedDiagnostic? = diagnostics.getOrNull(row)

    override fun getRowCount(): Int = diagnostics.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val item = diagnostics[rowIndex]
        return when (columnIndex) {
            0 -> item.severity.name.lowercase().replaceFirstChar(Char::titlecase)
            1 -> item.packageRoot?.fileName?.toString() ?: "Workspace"
            2 -> item.summary
            3 -> item.actions.firstOrNull()?.title ?: "—"
            else -> ""
        }
    }
}
