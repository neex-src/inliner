package com.neex.inliner.focus

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiCatchSection
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIfStatement
import com.intellij.psi.PsiLoopStatement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiSwitchBlock
import com.intellij.psi.PsiTryStatement
import com.neex.inliner.Keys

/**
 * Fold-to-focus: inside every scope method, collapse each run of lines the view's [FoldRule]
 * doesn't keep into an expandable "⋯ N lines" region. Kept visible besides the rule's lines:
 * the method signature and closing brace, control-flow headers (if/loop/switch/try/catch) that
 * enclose a kept line, and the call lines of live inline expansions in this editor.
 */
object FocusFolder {

    fun refresh(editor: Editor, set: FocusSet, psiFile: PsiFile, picture: FocusPicture) {
        clear(editor)
        if (!set.foldToFocus) return
        val doc = editor.document
        val scopes = set.enabledEntries()
            .mapNotNull { it.scopePtr.element }
            .filter { it.containingFile == psiFile }
            .distinct()
        if (scopes.isEmpty()) return

        // Invariant: no line the rule keeps may ever fold.
        val kept = picture.byLine.filter { (_, touches) -> set.foldRule.keeps(touches) }
        val keep = HashSet<Int>(kept.keys)

        // control-flow headers above every kept occurrence
        for (touch in kept.values.flatten()) {
            var el: PsiElement? = psiFile.findElementAt(touch.range.startOffset)
            while (el != null && el !is PsiMethod && el !is PsiFile) {
                if (isControlFlowHeader(el)) keep += doc.getLineNumber(el.textRange.startOffset)
                el = el.parent
            }
        }

        // method signature (through the opening brace) and closing brace
        val spans = mutableListOf<IntRange>()
        for (m in scopes) {
            if (m.textRange.endOffset > doc.textLength) continue
            val start = doc.getLineNumber(m.textRange.startOffset)
            val end = doc.getLineNumber(m.textRange.endOffset)
            val bodyOpen = m.body?.lBrace?.let { doc.getLineNumber(it.textRange.startOffset) } ?: start
            for (line in start..bodyOpen) keep += line
            keep += end
            spans += start..end
        }

        // anchor lines of live inline expansions embedded in this editor
        set.hostEditor.getUserData(Keys.HOST_STATE)?.let { state ->
            for (node in state.creationOrder) {
                if (!node.disposed && node.parentEditor === editor && node.callRange.isValid) {
                    keep += doc.getLineNumber(node.callRange.startOffset)
                    keep += doc.getLineNumber(node.callRange.endOffset)
                }
            }
        }

        val ours = mutableListOf<com.intellij.openapi.editor.FoldRegion>()
        editor.foldingModel.runBatchFoldingOperation {
            for (span in spans) {
                var run = -1
                for (line in span.first..span.last + 1) {
                    val isKeep = line > span.last || line in keep
                    if (!isKeep && run == -1) {
                        run = line
                    } else if (isKeep && run != -1) {
                        val n = line - run
                        val region = editor.foldingModel.addFoldRegion(
                            doc.getLineStartOffset(run),
                            doc.getLineEndOffset(line - 1),
                            "⋯ $n line" + (if (n > 1) "s" else ""),
                        )
                        // null on conflict with an existing region — skip silently
                        region?.let { it.isExpanded = false; ours += it }
                        run = -1
                    }
                }
            }
        }
        editor.putUserData(FocusKeys.FOLD_REGIONS, ours)
    }

    fun clear(editor: Editor) {
        val ours = editor.getUserData(FocusKeys.FOLD_REGIONS) ?: return
        editor.foldingModel.runBatchFoldingOperation {
            ours.forEach { region ->
                if (region.isValid) editor.foldingModel.removeFoldRegion(region)
            }
        }
        editor.putUserData(FocusKeys.FOLD_REGIONS, null)
    }

    fun ourRegions(editor: Editor): List<com.intellij.openapi.editor.FoldRegion> =
        editor.getUserData(FocusKeys.FOLD_REGIONS) ?: emptyList()

    private fun isControlFlowHeader(el: PsiElement): Boolean =
        el is PsiIfStatement || el is PsiLoopStatement || el is PsiSwitchBlock ||
            el is PsiTryStatement || el is PsiCatchSection
}
