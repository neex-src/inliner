package com.neex.inliner

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.util.Disposer

/**
 * The plugin's lifetime. Per-editor state (expansions, focus sets) must go when its editor
 * closes AND when the plugin unloads (update, disable): otherwise frames and highlights of the
 * old plugin stay painted in open editors, owned by classes that no longer exist.
 */
@Service(Service.Level.APP)
class InlinerLifetime : Disposable {
    override fun dispose() = Unit

    companion object {
        /** Disposes [state] with [editor] or with the plugin, whichever goes first. */
        fun bind(editor: Editor, state: Disposable) {
            Disposer.register(ApplicationManager.getApplication().getService(InlinerLifetime::class.java), state)
            val editorLink = Disposable { Disposer.dispose(state) }
            EditorUtil.disposeWithEditor(editor, editorLink)
            // Gone first with the plugin: take the link off the editor too, or the editor keeps
            // one of our lambdas — and with it the unloaded plugin's class loader.
            Disposer.register(state) { Disposer.dispose(editorLink) }
        }
    }
}
