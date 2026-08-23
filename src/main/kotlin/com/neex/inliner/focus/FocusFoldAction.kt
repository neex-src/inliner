package com.neex.inliner.focus

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

class FocusFoldAction : AnAction() {

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
        val set = FocusService.focusSet(editor, project, create = false)
        if (set == null || set.entries.isEmpty()) {
            if (editor.component.isShowing) {
                HintManager.getInstance().showInformationHint(editor, "No focused symbols — add some first (Alt+. or Alt+;)")
            }
            return
        }
        set.foldToFocus = !set.foldToFocus
        FocusService.refreshAll(set)
    }
}
