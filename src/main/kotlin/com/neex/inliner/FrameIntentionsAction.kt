package com.neex.inliner

import com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiDocumentManager

/**
 * Alt+Enter in a frame: the platform's own intention list for the callee at the frame's caret —
 * same list, preview, options and quick fixes as in the callee's file, and the chosen one edits
 * that file. Runs inside a [FrameEditSession], since the platform shows nothing in a viewer.
 *
 * Bound to whatever Show Intention Actions is bound to; [FrameIntentionsPromoter] puts it first
 * in a frame, and outside frames it is off, so the platform's runs.
 */
class FrameIntentionsAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR)?.let(::frameOf) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val node = frameOf(editor) ?: return
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        FrameEditSession.open(node)
        ShowIntentionActionsHandler().invoke(project, editor, psiFile, false)
    }
}

class FrameIntentionsPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction>? {
        val ours = actions.filterIsInstance<FrameIntentionsAction>()
        if (ours.isEmpty() || CommonDataKeys.EDITOR.getData(context)?.let(::frameOf) == null) return null
        return ours + actions.filterNot { it is FrameIntentionsAction }
    }
}

/** The frame [editor] renders, if it takes intentions (a decompiled mirror's PSI isn't its document's). */
private fun frameOf(editor: Editor): ExpansionNode? =
    editor.getUserData(Keys.FRAME_NODE)?.takeIf { !it.disposed && it.pinnedPsiFile == null }
