package tech.zpkg.intellij.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import tech.zpkg.intellij.service.ZedProjectService

class RefreshZedStateAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<ZedProjectService>()?.refresh(showNotification = true)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }
}
