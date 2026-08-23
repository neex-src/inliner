package com.neex.inliner.focus

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font

/** Which lines/identifier ranges the enabled focus entries touch in one file. */
class FocusTouches {
    /** line → color indexes of every tracked symbol referenced on that line */
    val lineColors = HashMap<Int, MutableSet<Int>>()

    /** identifier occurrence → color index */
    val idRanges = mutableListOf<Pair<TextRange, Int>>()

    /** absolute offsets of every occurrence, for control-flow ancestry lookups */
    val touchOffsets = mutableListOf<Int>()
}

object FocusHighlighter {

    // Between caret-row (1000) and selection (9000): line tint low, identifier tint higher.
    private const val LAYER_LINE = HighlighterLayer.CARET_ROW + 1
    private const val LAYER_ID = HighlighterLayer.SELECTION - 1

    fun computeTouches(set: FocusSet, psiFile: PsiFile, doc: Document): FocusTouches {
        val touches = FocusTouches()
        for (entry in set.enabledEntries()) {
            val scope = entry.scopePtr.element ?: continue
            if (scope.containingFile != psiFile) continue
            val target = entry.ptr.element ?: continue

            val ranges = mutableListOf<TextRange>()
            target.nameIdentifier?.let { id ->
                if (PsiTreeUtil.isAncestor(scope, id, false)) ranges += id.textRange
            }
            for (ref in ReferencesSearch.search(target, LocalSearchScope(scope)).findAll()) {
                val base = ref.element.textRange?.startOffset ?: continue
                ranges += ref.rangeInElement.shiftRight(base)
            }

            for (r in ranges) {
                if (r.endOffset > doc.textLength) continue
                touches.idRanges += r to entry.colorIndex
                touches.touchOffsets += r.startOffset
                val startLine = doc.getLineNumber(r.startOffset)
                val endLine = doc.getLineNumber(r.endOffset)
                for (line in startLine..endLine) {
                    touches.lineColors.getOrPut(line) { mutableSetOf() } += entry.colorIndex
                }
            }
        }
        return touches
    }

    fun apply(editor: Editor, touches: FocusTouches) {
        clear(editor)
        val ours = mutableListOf<RangeHighlighter>()
        val mm = editor.markupModel
        for ((line, colors) in touches.lineColors) {
            if (line >= editor.document.lineCount) continue
            val bg = if (colors.size == 1) lineBg(colors.first()) else multiBg()
            ours += mm.addLineHighlighter(line, LAYER_LINE, TextAttributes(null, bg, null, null, Font.PLAIN))
        }
        for ((range, colorIndex) in touches.idRanges) {
            if (range.endOffset > editor.document.textLength) continue
            ours += mm.addRangeHighlighter(
                range.startOffset, range.endOffset, LAYER_ID,
                TextAttributes(null, idBg(colorIndex), null, null, Font.PLAIN),
                HighlighterTargetArea.EXACT_RANGE,
            )
        }
        editor.putUserData(FocusKeys.HIGHLIGHTERS, ours)
    }

    fun clear(editor: Editor) {
        editor.getUserData(FocusKeys.HIGHLIGHTERS)?.forEach { h ->
            if (h.isValid) editor.markupModel.removeHighlighter(h)
        }
        editor.putUserData(FocusKeys.HIGHLIGHTERS, null)
    }

    fun ourHighlighters(editor: Editor): List<RangeHighlighter> =
        editor.getUserData(FocusKeys.HIGHLIGHTERS) ?: emptyList()

    private fun lineBg(i: Int): Color = ColorUtil.toAlpha(FocusPalette.color(i), 26)

    private fun idBg(i: Int): Color = ColorUtil.toAlpha(FocusPalette.color(i), 90)

    /** A line touched by several tracked symbols: neutral tint, no winner-takes-all. */
    private fun multiBg(): Color = ColorUtil.toAlpha(JBColor.GRAY, 30)
}
