package com.neex.inliner

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.openapi.wm.WindowManager
import com.neex.inliner.recorder.ide.RecorderService
import com.neex.inliner.replay.ReplayService

/**
 * One line in the status bar for what runs in the background of the editor: a recording being
 * made ("Recording — 1,234 lines"), or the replay's place. Not the status bar's info text: the
 * editor overwrites that with the problem at the caret on every move.
 */
class InlinerStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    /** Never creates the services: asked while the plugin unloads, that would make new ones nobody disposes. */
    override fun getText(): String = textOf(project)

    override fun getTooltipText(): String? = getText().ifEmpty { null }

    override fun getAlignment(): Float = 0f

    override fun install(statusBar: StatusBar) = Unit

    override fun dispose() = Unit

    companion object {
        const val ID = "com.neex.inliner.status"

        fun textOf(project: Project): String =
            project.getServiceIfCreated(RecorderService::class.java)?.statusText?.ifEmpty { null }
                ?: project.getServiceIfCreated(ReplayService::class.java)?.statusText
                ?: ""

        /** The item comes and goes with what it shows: the factory is asked again, then the text is redrawn. */
        fun update(project: Project) {
            if (project.isDisposed) return
            project.getServiceIfCreated(StatusBarWidgetsManager::class.java)?.updateWidget(InlinerStatusWidgetFactory::class.java)
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(ID)
        }
    }
}

class InlinerStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = InlinerStatusWidget.ID

    override fun getDisplayName(): String = "Inline Reading Mode: recording and replay"

    /** Only while recording or replaying: an empty item in every project's status bar is noise. */
    override fun isAvailable(project: Project): Boolean = InlinerStatusWidget.textOf(project).isNotEmpty()

    override fun createWidget(project: Project): StatusBarWidget = InlinerStatusWidget(project)
}
