package com.neex.inliner

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key

/**
 * A frame that takes edits for one explicit flow — Alt+Enter and whatever the chosen intention
 * goes on to do, an Undo — and is a viewer again as soon as the user acts on it outside that flow.
 *
 * A frame is a viewer so typing can't edit the callee by accident, and viewer is the only guard
 * the platform has per editor (document writability is per file: it would lock the real file's
 * editor too). But the platform also turns off what edits on purpose in a viewer: intentions
 * (both listing them and running the chosen one), undo and redo. So the frame stops being a
 * viewer for the flow ([FrameEditAction] opens it).
 *
 * The flow has no reliable end to wait for: the chosen intention runs after the popup closes and
 * often goes on later still (in-place introduce and rename start their template from a later
 * event). So the session doesn't guess when the flow is over; it ends at the first keystroke or
 * action aimed at the frame while nothing of the flow is still up — no popup, template or lookup —
 * just before that keystroke or action runs, which then meets a viewer. Every edit made through
 * an editor arrives as one of those, so outside the flow nothing edits the frame.
 */
class FrameEditSession private constructor(private val node: ExpansionNode) : Disposable {

    private val editor get() = node.fragmentEditor

    /** True while [FrameEditAction] runs the platform's action: that action is the flow itself. */
    private var dispatching = false

    fun dispatching(run: () -> Unit) {
        dispatching = true
        try {
            run()
        } finally {
            dispatching = false
        }
    }

    private fun start() {
        editor.isViewer = false
        editor.putUserData(SESSION, this)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(AnActionListener.TOPIC, object : AnActionListener {
            override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
                if (event.getData(CommonDataKeys.EDITOR) === editor) userActs()
            }

            override fun beforeEditorTyping(c: Char, dataContext: DataContext) {
                if (CommonDataKeys.EDITOR.getData(dataContext) === editor) userActs()
            }
        })
    }

    private fun userActs() {
        if (!dispatching && !flowIsUp()) Disposer.dispose(this)
    }

    private fun flowIsUp(): Boolean {
        val project = editor.project ?: return false
        return TemplateManager.getInstance(project).getActiveTemplate(editor) != null ||
            LookupManager.getActiveLookup(editor) != null
    }

    override fun dispose() {
        if (editor.getUserData(SESSION) === this) editor.putUserData(SESSION, null)
        if (!editor.isDisposed) {
            editor.isViewer = true
            FrameCaret.clamp(node)
        }
    }

    companion object {
        private val SESSION: Key<FrameEditSession> = Key.create("inliner.frameEditSession")

        /** [node]'s frame open for edits, until the user next acts on it outside the flow. */
        fun open(node: ExpansionNode): FrameEditSession {
            node.fragmentEditor.getUserData(SESSION)?.let { return it }
            return FrameEditSession(node).also {
                Disposer.register(node, it)
                it.start()
            }
        }

        fun isOpen(editor: Editor): Boolean = editor.getUserData(SESSION) != null
    }
}
