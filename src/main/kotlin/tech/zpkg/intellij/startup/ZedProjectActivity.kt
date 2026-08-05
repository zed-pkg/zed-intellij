package tech.zpkg.intellij.startup

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import tech.zpkg.intellij.service.ZedProjectService

class ZedProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<ZedProjectService>().start()
    }
}
