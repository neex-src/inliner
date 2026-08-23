package com.neex.inliner

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler

/**
 * Esc collapses the innermost / most recent expansion reachable from the editor that
 * received the keystroke; with no live expansions it delegates, so stock Esc is untouched.
 */
class InlineEscapeHandler(private val original: EditorActionHandler) : EditorActionHandler() {

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        val target = escapeTarget(editor)
        if (target != null) {
            InlineExpansionService.collapse(target)
        } else {
            original.execute(editor, caret, dataContext)
        }
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        escapeTarget(editor) != null || original.isEnabled(editor, caret, dataContext)

    private fun escapeTarget(editor: Editor): ExpansionNode? {
        val frame = editor.getUserData(Keys.FRAME_NODE)
        if (frame != null) {
            return frame.hostState.creationOrder.lastOrNull { it === frame || frame.isAncestorOf(it) }
        }
        return editor.getUserData(Keys.HOST_STATE)?.creationOrder?.lastOrNull()
    }
}
