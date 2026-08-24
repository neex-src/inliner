package com.neex.inliner.focus

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font

/** One identifier occurrence of a tracked (or flow-derived) variable. */
data class IdMark(val range: TextRange, val colorIndex: Int, val direct: Boolean)

/** Which lines/identifier ranges the enabled focus entries touch in one file. */
class FocusTouches {
    /** line → colors of tracked symbols directly referenced on that line */
    val lineColors = HashMap<Int, MutableSet<Int>>()

    /** line → colors reaching the line only through transitive flow */
    val propagatedLineColors = HashMap<Int, MutableSet<Int>>()

    val idRanges = mutableListOf<IdMark>()

    /** absolute offsets of every occurrence, for control-flow ancestry lookups */
    val touchOffsets = mutableListOf<Int>()

    /** every line the focus touches — the fold keep-set MUST contain all of these */
    fun allTouchedLines(): Set<Int> = lineColors.keys + propagatedLineColors.keys
}

object FocusHighlighter {

    // Between caret-row (1000) and selection (9000): line tint low, identifier tint higher.
    private const val LAYER_LINE = HighlighterLayer.CARET_ROW + 1
    private const val LAYER_ID = HighlighterLayer.SELECTION - 1

    fun computeTouches(set: FocusSet, psiFile: PsiFile, doc: Document): FocusTouches {
        val touches = FocusTouches()
        val edgesCache = HashMap<PsiMethod, FocusFlow.Edges>()
        for (entry in set.enabledEntries()) {
            val scope = entry.scopePtr.element ?: continue
            if (scope.containingFile != psiFile) continue
            val target = entry.ptr.element ?: continue

            val variables: Map<PsiVariable, Boolean> = if (set.transitive) {
                val edges = edgesCache.getOrPut(scope) { FocusFlow.edgesOf(scope) }
                FocusFlow.closure(edges, target).associateWith { it == target }
            } else {
                mapOf(target to true)
            }

            for ((variable, direct) in variables) {
                for (r in occurrences(variable, scope)) {
                    if (r.endOffset > doc.textLength) continue
                    touches.idRanges += IdMark(r, entry.colorIndex, direct)
                    touches.touchOffsets += r.startOffset
                    val bucket = if (direct) touches.lineColors else touches.propagatedLineColors
                    for (line in doc.getLineNumber(r.startOffset)..doc.getLineNumber(r.endOffset)) {
                        bucket.getOrPut(line) { mutableSetOf() } += entry.colorIndex
                    }
                }
            }
        }
        // a line with a direct hit renders as direct; drop it from the propagated bucket
        touches.lineColors.keys.forEach { touches.propagatedLineColors.remove(it) }
        return touches
    }

    private fun occurrences(variable: PsiVariable, scope: PsiMethod): List<TextRange> {
        val ranges = mutableListOf<TextRange>()
        variable.nameIdentifier?.let { id ->
            if (PsiTreeUtil.isAncestor(scope, id, false)) ranges += id.textRange
        }
        for (ref in ReferencesSearch.search(variable, LocalSearchScope(scope)).findAll()) {
            val base = ref.element.textRange?.startOffset ?: continue
            ranges += ref.rangeInElement.shiftRight(base)
        }
        return ranges
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
        for ((line, colors) in touches.propagatedLineColors) {
            if (line >= editor.document.lineCount) continue
            val bg = if (colors.size == 1) propagatedLineBg(colors.first()) else propagatedMultiBg()
            ours += mm.addLineHighlighter(line, LAYER_LINE, TextAttributes(null, bg, null, null, Font.PLAIN))
        }
        for (mark in touches.idRanges) {
            if (mark.range.endOffset > editor.document.textLength) continue
            val bg = if (mark.direct) idBg(mark.colorIndex) else propagatedIdBg(mark.colorIndex)
            ours += mm.addRangeHighlighter(
                mark.range.startOffset, mark.range.endOffset, LAYER_ID,
                TextAttributes(null, bg, null, null, Font.PLAIN),
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

    /** Downstream/upstream of a tracked value: same hue, visibly weaker than a direct hit. */
    private fun propagatedLineBg(i: Int): Color = ColorUtil.toAlpha(FocusPalette.color(i), 13)

    private fun propagatedIdBg(i: Int): Color = ColorUtil.toAlpha(FocusPalette.color(i), 45)

    /** A line touched by several tracked symbols: neutral tint, no winner-takes-all. */
    private fun multiBg(): Color = ColorUtil.toAlpha(JBColor.GRAY, 30)

    private fun propagatedMultiBg(): Color = ColorUtil.toAlpha(JBColor.GRAY, 15)
}
