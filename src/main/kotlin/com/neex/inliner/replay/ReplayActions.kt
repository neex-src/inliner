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
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil
import com.neex.inliner.recorder.ValueRenderer

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

/** Back to the line that gave the variable at the caret its value — or the call that passed it in. */
class ReplayLastChangeAction : ReplayAction() {
    override fun perform(service: ReplayService, editor: Editor) {
        variableAtCaret(editor)?.let { service.toChange(it, forward = false, editor) }
    }
}

/** On to the next line that changes the variable at the caret. */
class ReplayNextChangeAction : ReplayAction() {
    override fun perform(service: ReplayService, editor: Editor) {
        variableAtCaret(editor)?.let { service.toChange(it, forward = true, editor) }
    }
}

/** Fold away what the cursor's call didn't run; again to unfold. */
class ReplayFoldAction : ReplayAction() {
    override fun perform(service: ReplayService, editor: Editor) = service.toggleFold()
}

/** The recording's threads; Enter replays that one. */
class ReplayThreadsAction : ReplayAction() {
    override fun perform(service: ReplayService, editor: Editor) {
        val replay = service.replay ?: return
        val counts = replay.recording.hits.filter { it.startsLine }.groupingBy { it.thread }.eachCount()
        val items = replay.recording.threads.filterKeys { it in counts }.map { (id, name) ->
            Labeled("$name  ·  ${"%,d".format(counts.getValue(id))} lines" + if (id == replay.thread) "  (replaying)" else "") { service.selectThread(id) }
        }
        choose(editor, "Replay a Thread", items)
    }
}

/** This thread's calls as a tree; Enter puts the cursor on the call's first line. */
class ReplayCallsAction : ReplayAction() {
    override fun perform(service: ReplayService, editor: Editor) {
        val replay = service.replay ?: return
        val current = replay.callOf(replay.current)
        val items = replay.callList().map { (call, level) ->
            val first = call.hits.first()
            val args = first.values.filter { it.name != "this" }.joinToString(", ") { "${it.name} = ${it.text}" }.take(80)
            val label = "  ".repeat(level) + ValueRenderer.simple(call.method.className) + "." + call.method.methodName + "($args)" +
                if (call === current) "  ◀" else ""
            Labeled(label) { service.moveTo(first) }
        }
        choose(editor, "Go to a Call", items, items.indexOfFirst { it.label.endsWith("◀") })
    }
}

internal class Labeled(val label: String, val run: () -> Unit) {
    override fun toString() = label
}

internal fun choose(editor: Editor, title: String, items: List<Labeled>, selected: Int = 0) {
    if (items.isEmpty()) return
    JBPopupFactory.getInstance().createPopupChooserBuilder(items)
        .setTitle(title)
        .setNamerForFiltering { it.label }
        .setSelectedValue(items[selected.coerceIn(items.indices)], true)
        .setItemChosenCallback { it.run() }
        .createPopup()
        .showInBestPositionFor(editor)
}

/** The local or parameter named at the caret: a reference to it, or its declaration. */
internal fun variableAtCaret(editor: Editor): String? {
    val project = editor.project ?: return null
    return ReadAction.computeBlocking<String?, Throwable> {
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@computeBlocking null
        val offset = editor.caretModel.offset
        val leaf = file.findElementAt(offset)?.takeIf { it is PsiIdentifier } ?: file.findElementAt(offset - 1)
        when (val owner = PsiTreeUtil.getParentOfType(leaf, PsiReferenceExpression::class.java, PsiVariable::class.java)) {
            is PsiReferenceExpression -> owner.referenceName
            is PsiVariable -> owner.name
            else -> null
        }
    }
}

/** A what-if being replayed: show the original run instead, and back. */
class ReplayCompareToggleAction : ReplayAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = e.presentation.isEnabled && e.project?.let(ReplayService::of)?.comparison != null
    }

    override fun perform(service: ReplayService, editor: Editor) = service.toggleCompared()
}

/** A what-if being replayed: to where it and the original part. */
class ReplayDivergenceAction : ReplayAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = e.presentation.isEnabled && e.project?.let(ReplayService::of)?.comparison != null
    }

    override fun perform(service: ReplayService, editor: Editor) = service.toDivergence(editor)
}
