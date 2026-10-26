package com.neex.inliner.focus

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/** Next [FoldRule] for this view; folds to focus if it wasn't. The default lives in Settings. */
class FocusFoldRuleAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        e.presentation.isEnabled = editor != null && project != null &&
            FocusService.focusSet(editor, project, create = false)?.entries?.isNotEmpty() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val set = FocusService.focusSet(editor, project, create = false) ?: return
        set.foldRule = set.foldRule.next()
        set.foldToFocus = true
        FocusService.refreshAll(set)
        if (editor.component.isShowing) {
            HintManager.getInstance().showInformationHint(editor, "Fold to focus keeps: ${set.foldRule.title}")
        }
    }
}
