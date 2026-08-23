package com.neex.inliner.focus

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile

object FocusFolder {

    /** Fold-to-focus lands in a later milestone; the hook keeps refresh wiring stable. */
    fun refresh(editor: Editor, set: FocusSet, psiFile: PsiFile, touches: FocusTouches) {
        if (!set.foldToFocus) {
            clear(editor)
        }
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
}
