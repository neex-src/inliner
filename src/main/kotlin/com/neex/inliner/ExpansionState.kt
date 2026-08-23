package com.neex.inliner

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPsiElementPointer
import javax.swing.JPanel

object Keys {
    /** Lives on the host (real file) editor; owns all expansions rooted there. */
    val HOST_STATE: Key<HostState> = Key.create("inliner.hostState")

    /** Lives on each fragment viewer editor; identifies the frame it renders. */
    val FRAME_NODE: Key<ExpansionNode> = Key.create("inliner.frameNode")
}

class HostState(val hostEditor: Editor) : Disposable {
    /** Expansions embedded directly in the host editor. */
    val roots = mutableListOf<ExpansionNode>()

    /** All live nodes in creation order — Esc collapses the most recent reachable one. */
    val creationOrder = mutableListOf<ExpansionNode>()

    override fun dispose() {
        // Nodes are Disposer children of this state (or of their parent node);
        // Disposer tears them down before this runs.
        roots.clear()
        creationOrder.clear()
        hostEditor.putUserData(Keys.HOST_STATE, null)
    }
}

class ExpansionNode(
    val hostState: HostState,
    val parent: ExpansionNode?,
    val parentEditor: EditorEx,
    /** The call expression's range in [parentEditor]'s document. */
    val callRange: RangeMarker,
    val methodPtr: SmartPsiElementPointer<PsiMethod>,
    /** The method's range in the callee file's document. */
    val methodRange: RangeMarker,
    /**
     * Set when the fragment renders a detached document (e.g. a decompiled mirror that has
     * no real Document): PSI for caret/resolve inside the frame comes from here, since
     * PsiDocumentManager knows nothing about the detached document.
     */
    val pinnedPsiFile: PsiFile? = null,
) : Disposable {
    val children = mutableListOf<ExpansionNode>()

    lateinit var inlay: Inlay<*>
    lateinit var fragmentEditor: EditorEx
    lateinit var wrapper: JPanel

    var disposed = false
        private set

    fun isAncestorOf(other: ExpansionNode): Boolean {
        var n: ExpansionNode? = other.parent
        while (n != null) {
            if (n === this) return true
            n = n.parent
        }
        return false
    }

    override fun dispose() {
        disposed = true
        (parent?.children ?: hostState.roots).remove(this)
        hostState.creationOrder.remove(this)
        callRange.dispose()
        methodRange.dispose()
        if (::inlay.isInitialized && inlay.isValid) Disposer.dispose(inlay)
        if (::fragmentEditor.isInitialized && !fragmentEditor.isDisposed) {
            EditorFactory.getInstance().releaseEditor(fragmentEditor)
        }
    }
}
