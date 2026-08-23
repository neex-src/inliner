package com.neex.inliner

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.PsiJavaFile

class ToggleInlineAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // Derive PSI from the editor's document, never from PSI_FILE: inside an embedded
        // fragment editor the data context's PSI_FILE points at the host file.
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        e.presentation.isEnabled = editor != null && project != null &&
            InlineExpansionService.psiFileFor(editor, project) is PsiJavaFile
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        InlineExpansionService.toggle(editor, project)
    }
}
