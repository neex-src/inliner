package com.neex.inliner.replay

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.neex.inliner.recorder.ide.RecordingStore
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Alt+Shift+P: the project's recordings, newest first — Enter replays one; or close the open replay. */
class ReplayPickAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val choices = choices(project)
            ApplicationManager.getApplication().invokeLater({ show(project, choices) }, ModalityState.nonModal(), project.disposed)
        }
    }

    internal class Choice(val label: String, val run: () -> Unit) {
        override fun toString() = label
    }

    internal fun choices(project: Project, store: RecordingStore = RecordingStore.of(project)): List<Choice> {
        val service = ReplayService.of(project)
        val out = ArrayList<Choice>()
        if (service.isOpen) out += Choice("Close the replay") { service.close() }
        store.list().take(MAX).forEach { file -> out += Choice(label(store, file)) { service.open(file) } }
        return out
    }

    /** Newest first, each labelled from its header: a list of thirty reads thirty headers, not thirty runs. */
    private fun label(store: RecordingStore, file: Path): String {
        val name = file.fileName.toString().substringBefore('.')
        val time = runCatching { LocalDateTime.parse(name.take(15), STAMP).format(SHOWN) }.getOrDefault(name)
        val header = store.header(file) ?: return "$time  (unreadable)"
        val entry = header.entry?.let { it.first.substringAfterLast('.') + "." + it.second } ?: "empty"
        return "$time  $entry  ·  ${"%,d".format(header.hits)} lines, ${"%,d".format(header.calls)} calls"
    }

    private fun show(project: Project, choices: List<Choice>) {
        if (choices.isEmpty()) {
            JBPopupFactory.getInstance().createMessage("No recordings yet: Alt+Shift+D in a debug session records one.").showCenteredInCurrentWindow(project)
            return
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
            .setTitle("Replay a Recording")
            .setNamerForFiltering { it.label }
            .setItemChosenCallback { it.run() }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private companion object {
        const val MAX = 30
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val SHOWN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm:ss")
    }
}
