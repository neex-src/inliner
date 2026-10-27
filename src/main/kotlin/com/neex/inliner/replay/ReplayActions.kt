package com.neex.inliner.replay

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

/**
 * A replay key: live while a replay is open, in a source editor, and no debugger is paused — the
 * debugger's own keys (F8, F7, …) then move the replay's cursor instead ([ReplayPromoter]).
 */
abstract class ReplayAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = live(e.project, e.getData(CommonDataKeys.EDITOR))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        perform(ReplayService.of(project), e.getData(CommonDataKeys.EDITOR) ?: return)
    }

    abstract fun perform(service: ReplayService, editor: Editor)

    companion object {
        fun live(project: Project?, editor: Editor?): Boolean {
            val service = project?.let(ReplayService::of) ?: return false
            return service.isOpen && editor != null && editor.editorKind == EditorKind.MAIN_EDITOR && !service.debuggerPaused()
        }
    }
}

class ReplayStepIntoAction : ReplayAction() {
    override fun perform(service: ReplayService, editor: Editor) = service.move(Step.INTO, editor)
}

class ReplayStepOverAction : ReplayAction() {
    override fun perform(service: ReplayService, editor: Editor) = service.move(Step.OVER, editor)
}

class ReplayStepOutAction : ReplayAction() {
    override fun perform(service: ReplayService, editor: Editor) = service.move(Step.OUT, editor)
}

class ReplayBackIntoAction : ReplayAction() {
    override fun perform(service: ReplayService, editor: Editor) = service.move(Step.BACK_INTO, editor)
}

class ReplayBackOverAction : ReplayAction() {
    override fun perform(service: ReplayService, editor: Editor) = service.move(Step.BACK_OVER, editor)
}

class ReplayToLineAction : ReplayAction() {
    override fun perform(service: ReplayService, editor: Editor) = service.toCaretLine(editor, forward = true)
}

class ReplayBackToLineAction : ReplayAction() {
    override fun perform(service: ReplayService, editor: Editor) = service.toCaretLine(editor, forward = false)
}

class ReplayPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction>? {
        val ours = actions.filterIsInstance<ReplayAction>()
        if (ours.isEmpty() || !ReplayAction.live(CommonDataKeys.PROJECT.getData(context), CommonDataKeys.EDITOR.getData(context))) return null
        return ours + actions.filterNot { it is ReplayAction }
    }
}
