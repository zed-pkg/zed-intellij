package tech.zpkg.intellij.service

import com.intellij.util.messages.Topic
import tech.zpkg.intellij.model.ZedWorkspaceSnapshot

fun interface ZedStateListener {
    fun stateChanged(snapshot: ZedWorkspaceSnapshot)

    companion object {
        @JvmField
        val TOPIC: Topic<ZedStateListener> = Topic.create(
            "Zed package workspace state",
            ZedStateListener::class.java,
        )
    }
}
