package com.neex.inliner.focus

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil
import com.neex.inliner.ExpansionNode
import com.neex.inliner.InlineExpansionService
import com.neex.inliner.Keys

/**
 * Cross-frame propagation: a tracked variable passed as a direct reference argument at an
 * expanded call auto-tracks the matching callee parameter inside that frame — same color,
 * removed when the frame collapses or the root entry is removed.
 */
object FocusFrames {

    /**
     * Idempotent, called from refreshAll. creationOrder is parent-before-child, so a single
     * pass follows a tracked value through nested frames.
     */
    fun propagateAll(set: FocusSet) {
        if (set.entries.isEmpty()) return
        val state = set.hostEditor.getUserData(Keys.HOST_STATE) ?: return
        for (node in state.creationOrder.toList()) {
            if (!node.disposed) propagateInto(set, node)
        }
    }

    private fun propagateInto(set: FocusSet, node: ExpansionNode) {
        val callee = node.methodPtr.element ?: return
        if (!node.callRange.isValid) return
        val parentPsi = InlineExpansionService.psiFileFor(node.parentEditor, set.project) ?: return
        val leaf = parentPsi.findElementAt(node.callRange.startOffset) ?: return
        val call = PsiTreeUtil.getParentOfType(leaf, PsiCallExpression::class.java, false) ?: return
        val args = call.argumentList?.expressions ?: return
        val params = callee.parameterList.parameters

        var added = false
        for ((i, arg) in args.withIndex()) {
            if (i >= params.size) break // varargs tail: not propagated (v1)
            val target = (arg as? PsiReferenceExpression)?.resolve() as? PsiVariable ?: continue
            val entry = set.entries.firstOrNull { it.enabled && it.ptr.element == target } ?: continue
            val param = params[i]
            if (set.entries.any { it.ptr.element == param }) continue
            val child = FocusService.add(set, param, callee, parent = entry, colorIndex = entry.colorIndex)
            child.sourceNode = node
            added = true
        }
        if (added) installCleanup(set, node)
    }

    /** When the frame goes away, its derived entries go with it (and repaint what's left). */
    private fun installCleanup(set: FocusSet, node: ExpansionNode) {
        if (!set.cleanupNodes.add(node)) return
        val cleanup = Disposable {
            // Runs while the node is mid-disposal: mutate state now, but repaint later —
            // a synchronous refresh would re-propagate into the dying frame.
            set.cleanupNodes.remove(node)
            FocusService.removeDerivedFor(set, node)
            ApplicationManager.getApplication().invokeLater {
                if (!set.hostEditor.isDisposed) FocusService.refreshAll(set)
            }
        }
        if (!Disposer.tryRegister(node, cleanup)) {
            set.cleanupNodes.remove(node)
            FocusService.removeDerivedFor(set, node)
        }
    }
}
