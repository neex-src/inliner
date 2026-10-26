package com.neex.inliner.focus

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font

/**
 * Paints a [FocusPicture]: every touched line gets its entry's tint (weaker when reached only
 * through value flow, neutral gray where several entries share it), every occurrence its own
 * stronger tint — and where the value is written, a box in the entry's color.
 */
object FocusHighlighter {

    // Between caret-row (1000) and selection (9000): line tint low, identifier tint higher.
    private const val LAYER_LINE = HighlighterLayer.CARET_ROW + 1
    private const val LAYER_ID = HighlighterLayer.SELECTION - 1

    fun apply(editor: Editor, picture: FocusPicture) {
        clear(editor)
        val ours = mutableListOf<RangeHighlighter>()
        val mm = editor.markupModel
        for ((line, touches) in picture.byLine) {
            if (line >= editor.document.lineCount) continue
            // a line with a direct hit renders as direct
            val direct = touches.filter { it.direct }
            val colors = direct.ifEmpty { touches }.map { it.entry.colorIndex }.toSet()
            val bg = when {
                direct.isNotEmpty() -> if (colors.size == 1) lineBg(colors.first()) else multiBg()
                else -> if (colors.size == 1) propagatedLineBg(colors.first()) else propagatedMultiBg()
            }
            ours += mm.addLineHighlighter(line, LAYER_LINE, TextAttributes(null, bg, null, null, Font.PLAIN))
        }
        val markWrites = FocusSettings.getInstance().markWrites
        for (touch in picture.touches) {
            if (touch.range.endOffset > editor.document.textLength) continue
            val i = touch.entry.colorIndex
            val bg = if (touch.direct) idBg(i) else propagatedIdBg(i)
            val box = if (markWrites && touch.access.writes) writeBox(i, touch.direct) else null
            ours += mm.addRangeHighlighter(
                touch.range.startOffset, touch.range.endOffset, LAYER_ID,
                TextAttributes(null, bg, box, box?.let { EffectType.ROUNDED_BOX }, Font.PLAIN),
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

    /** Where the value is written: the entry's color at full strength, a little less when propagated. */
    private fun writeBox(i: Int, direct: Boolean): Color =
        if (direct) FocusPalette.color(i) else ColorUtil.toAlpha(FocusPalette.color(i), 140)

    /** A line touched by several tracked symbols: neutral tint, no winner-takes-all. */
    private fun multiBg(): Color = ColorUtil.toAlpha(JBColor.GRAY, 30)

    private fun propagatedMultiBg(): Color = ColorUtil.toAlpha(JBColor.GRAY, 15)
}
