package com.neex.inliner.focus

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.PsiJavaFile
import com.neex.inliner.InlineExpansionService

class FocusTransitiveAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        e.presentation.isEnabled = editor != null && project != null &&
            InlineExpansionService.psiFileFor(editor, project) is PsiJavaFile
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val set = FocusService.focusSet(editor, project, create = true)!!
        set.transitive = !set.transitive
        FocusService.refreshAll(set)
        if (editor.component.isShowing) {
            HintManager.getInstance().showInformationHint(
                editor,
                if (set.transitive) "Transitive focus ON — following value flow" else "Transitive focus OFF — direct references only",
            )
        }
    }
}
