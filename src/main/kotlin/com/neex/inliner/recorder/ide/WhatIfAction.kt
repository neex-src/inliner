package com.neex.inliner.recorder.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.neex.inliner.replay.variableAtCaret

/**
 * Paused at a breakpoint, caret on a variable: what if it were something else? Asks for the value,
 * sets it in the paused frame, records the rest of the method, and replays it beside the original
 * recorded from the same line.
 */
class WhatIfAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val service = e.project?.let(RecorderService::of)
        e.presentation.isEnabled = service != null && e.getData(CommonDataKeys.EDITOR) != null &&
            !service.isRecording && service.currentProcess() != null && service.isPaused()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val name = variableAtCaret(editor) ?: return Messages.showInfoMessage(project, "Put the caret on a local variable or a parameter.", "What If")
        val text = Messages.showInputDialog(project, "Run on from here as if $name were:", "What If", null) ?: return
        if (text.isBlank()) return
        RecorderService.of(project).whatIf(name, text)
    }
}
