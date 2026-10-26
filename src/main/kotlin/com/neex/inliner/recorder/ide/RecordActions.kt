package com.neex.inliner.recorder.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/** Start recording the debug session in front, or stop the recording that runs. */
class RecordToggleAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val service = e.project?.let(RecorderService::of)
        e.presentation.isEnabled = service != null && (service.isRecording || service.currentProcess() != null)
        e.presentation.text = if (service?.isRecording == true) "Stop Recording" else "Start Recording"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val service = RecorderService.of(e.project ?: return)
        if (service.isRecording) service.stop() else service.start(untilReturn = false)
    }
}

/** Paused at a breakpoint: record from here until this method returns, running there. */
class RecordUntilReturnAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val service = e.project?.let(RecorderService::of)
        e.presentation.isEnabled = service != null && !service.isRecording && service.currentProcess() != null && service.isPaused()
    }

    override fun actionPerformed(e: AnActionEvent) {
        RecorderService.of(e.project ?: return).start(untilReturn = true)
    }
}
