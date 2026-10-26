package com.neex.inliner.recorder.ide

import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.neex.inliner.recorder.CallTree
import com.neex.inliner.recorder.Invocation
import com.neex.inliner.recorder.Recorder
import com.neex.inliner.recorder.Recording
import javax.swing.Timer

/**
 * The project's recording: at most one live at a time, attached to the debug session in front.
 * While it runs the status bar counts the recorded lines; when it ends it is saved
 * ([RecordingStore]) and a notification says what it holds.
 */
@Service(Service.Level.PROJECT)
class RecorderService(private val project: Project) : Disposable {

    private var live: LiveRecording? = null
    private var ticker: Timer? = null

    val isRecording: Boolean get() = live?.ended == false

    /** The Java debug process of the session in front, if it is attached. */
    fun currentProcess(): DebugProcessImpl? =
        DebuggerManagerEx.getInstanceEx(project).context.debugProcess?.takeIf { it.isAttached }

    fun isPaused(): Boolean = DebuggerManagerEx.getInstanceEx(project).context.suspendContext != null

    /** Starts recording the current debug session; [untilReturn]: from the paused frame until it returns. */
    fun start(untilReturn: Boolean) {
        val process = currentProcess() ?: return notify("No Java debug session to record.", NotificationType.WARNING)
        if (isRecording) return
        ReadAction.nonBlocking<List<String>> { ProjectPackages.of(project) }
            .inSmartMode(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.nonModal()) { begin(process, it, untilReturn) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun begin(process: DebugProcessImpl, packages: List<String>, untilReturn: Boolean) {
        if (isRecording || !process.isAttached) return
        val settings = RecorderSettings.getInstance()
        val scope = settings.scope(packages)
        if (scope.include.isEmpty()) {
            return notify("Nothing to record: no Java sources in this project. Add packages in Settings | Tools | Inline Reading Mode.", NotificationType.WARNING)
        }
        val recording = LiveRecording(
            process,
            scope.include,
            { busy -> Recorder(scope, settings.budget(), busy = busy) },
            { result, reason -> ApplicationManager.getApplication().invokeLater({ finished(result, reason) }, ModalityState.nonModal()) },
        )
        Disposer.register(this, recording)
        live = recording
        recording.start(untilReturn) {
            if (untilReturn) ApplicationManager.getApplication().invokeLater { process.xdebugProcess?.session?.resume() }
        }
        ticker = Timer(500) { status("Recording — ${"%,d".format(recording.hits)} lines") }.apply { start() }
    }

    fun stop() {
        live?.stop()
    }

    private fun finished(recording: Recording, reason: EndReason) {
        live = null
        ticker?.stop()
        ticker = null
        status("")
        if (project.isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val file = RecordingStore.of(project).save(recording)
            notify(summary(recording, reason) + "\nSaved: ${file.fileName}", NotificationType.INFORMATION)
        }
    }

    private fun summary(recording: Recording, reason: EndReason): String {
        val calls = CallTree.build(recording).values.sumOf { roots -> roots.sumOf(::count) }
        val why = when (reason) {
            EndReason.STOPPED -> "Recording stopped"
            EndReason.RETURNED -> "Recorded until the method returned"
            EndReason.BUDGET -> "Recording ended: the budget is used up"
            EndReason.DETACHED -> "Recording ended with the debug session"
        }
        val cut = if (recording.exhausted.isEmpty()) "" else ", ${recording.exhausted.size} hot lines cut short"
        return "$why: ${"%,d".format(recording.hits.size)} lines, ${"%,d".format(calls)} calls, " +
            "${recording.threads.size} thread${if (recording.threads.size == 1) "" else "s"}$cut."
    }

    private fun count(call: Invocation): Int = 1 + call.children.sumOf(::count)

    private fun status(text: String) {
        WindowManager.getInstance().getStatusBar(project)?.info = text
    }

    private fun notify(text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Inline Reading Mode")
            .createNotification(text, type)
            .notify(project)
    }

    override fun dispose() {
        ticker?.stop()
    }

    companion object {
        fun of(project: Project): RecorderService = project.service()
    }
}
